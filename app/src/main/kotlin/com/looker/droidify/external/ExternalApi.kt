package com.looker.droidify.external

import android.util.Base64
import com.looker.droidify.datastore.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tiny REST client for the external-source feature, covering GitHub, GitLab and Codeberg (Gitea).
 * Reuses the app's shared Ktor [HttpClient]. Unauthenticated, so it's subject to each provider's
 * anonymous rate limit — plenty for occasionally adding a source and checking a handful of apps.
 */
@Singleton
class ExternalApi @Inject constructor(
    private val httpClient: HttpClient,
    private val settingsRepository: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Whether the most recent api.github.com request was rejected by the rate limit (HTTP 403/429 with
     *  no remaining quota). Best-effort hint, read right after a failed call on the same coroutine. */
    private var rateLimited = false

    /** The user's optional GitHub token, or null when unset. Sent only to api.github.com requests to
     *  lift the anonymous 60-requests/hour rate limit to 5000. */
    private suspend fun githubAuthToken(): String? =
        settingsRepository.getInitial().githubToken.trim().takeIf { it.isNotEmpty() }

    /** True when the last GitHub call was rate-limited *and* no token is configured — i.e. the moment
     *  to nudge the user that adding a token would lift the limit. */
    suspend fun shouldSuggestGithubToken(): Boolean = rateLimited && githubAuthToken() == null

    suspend fun latestReleaseFor(app: ExternalApp): Release? =
        latestRelease(app.provider, app.owner, app.repo, app.includePrereleases, app.apkFilter)

    /**
     * Best-effort application id of the app a source builds, read from its `build.gradle`'s
     * `applicationId` (falling back to `namespace`) — the same trick Obtainium uses. Knowing the
     * package id lets an already-installed app be matched, so its real on-device name and icon show
     * before the user ever installs through us. GitHub and Codeberg/Gitea expose file contents the same
     * way; GitLab isn't covered (falls back to the repo name + avatar). Returns null when no build file
     * or id can be found. Never throws.
     */
    suspend fun fetchPackageId(app: ExternalApp): String? = withContext(Dispatchers.IO) {
        if (app.provider == SourceProvider.GITLAB) return@withContext null
        for (path in BUILD_GRADLE_PATHS) {
            val source = readRepoFile(app, path) ?: continue
            for (regex in PACKAGE_ID_REGEXES) {
                regex.find(source)?.let { return@withContext it.groupValues[1] }
            }
        }
        null
    }

    /**
     * Reads a repo text file. GitHub goes through the raw CDN, which — unlike the REST API — isn't
     * subject to the 60-requests/hour anonymous limit, so build files / manifests / strings don't burn
     * the budget. Gitea/Codeberg uses its contents API (base64). Null when missing or on failure.
     */
    private suspend fun readRepoFile(app: ExternalApp, path: String): String? = when (app.provider) {
        SourceProvider.GITHUB -> fetchRaw(app, path)
        SourceProvider.CODEBERG -> {
            val url = "https://codeberg.org/api/v1/repos/${app.owner}/${app.repo}/contents/$path"
            runCatching { getText(url) }.getOrNull()?.let { decodeContentsBase64(it) }
        }

        SourceProvider.GITLAB -> null
    }

    /**
     * Best-effort launcher icons for the app, found in the source repo itself so the card can show the
     * real icon before anything is installed (Obtainium does the same). Reads the repo's full file tree
     * in a single request and returns the matching raster launcher icons (PNG/WebP) as raw URLs, best
     * first: highest-density square icon, then round, then adaptive-foreground, then other
     * launcher-named icons. Adaptive/vector (.xml) icons are skipped — they can't be rendered as a plain
     * image. Only GitHub is covered (same as [fetchPackageId]); other providers return an empty list and
     * fall back to the account avatar. Never throws.
     */
    suspend fun fetchIconCandidates(app: ExternalApp): List<String> = withContext(Dispatchers.IO) {
        val paths = fetchTreePaths(app)
        rankIconPaths(paths).map { rawUrl(app.owner, app.repo, it) }
    }

    /**
     * Repo metadata used to present an external app *before* it's installed: launcher-icon candidates
     * and the app's real user-facing name. A single repo-tree request drives both (the name then needs
     * the manifest + string file). GitHub only; other providers yield empty/null. Never throws.
     */
    suspend fun fetchRepoMetadata(app: ExternalApp): RepoMetadata = withContext(Dispatchers.IO) {
        if (app.provider != SourceProvider.GITHUB) return@withContext RepoMetadata()
        val paths = fetchTreePaths(app)
        if (paths.isEmpty()) return@withContext RepoMetadata()
        RepoMetadata(
            iconCandidates = rankIconPaths(paths).map { rawUrl(app.owner, app.repo, it) },
            appName = resolveAppName(app, paths),
        )
    }

    /** The repo's whole file tree (blob paths), or empty on any failure / non-GitHub provider. */
    private suspend fun fetchTreePaths(app: ExternalApp): List<String> {
        if (app.provider != SourceProvider.GITHUB) return emptyList()
        val url = "https://api.github.com/repos/${app.owner}/${app.repo}/git/trees/HEAD?recursive=1"
        val text = runCatching { getText(url, github = true) }.getOrNull() ?: return emptyList()
        return runCatching { parseTreePaths(text) }.getOrNull() ?: emptyList()
    }

    /**
     * Resolves the app's user-facing name from its manifest's `<application android:label>` — the same
     * value the launcher shows. A literal label is used as-is; an `@string/name` reference is resolved
     * from the module's `res/values` string files (split files included). Tries the manifests most
     * likely to be the app module first, skipping library modules that carry no application label.
     * Returns null when it can't be determined (the UI then keeps the repo name).
     */
    private suspend fun resolveAppName(app: ExternalApp, paths: List<String>): String? {
        for (manifestPath in pickManifestPaths(paths)) {
            val xml = fetchRaw(app, manifestPath) ?: continue
            val label = extractApplicationLabel(xml) ?: continue
            val stringName = label.removePrefix("@string/").takeIf { it != label }
            if (stringName == null) {
                return unescapeAndroidString(label).takeIf { it.isNotBlank() }
            }
            val moduleRoot = manifestPath.removeSuffix("/src/main/AndroidManifest.xml")
            resolveStringResource(app, paths, moduleRoot, stringName)?.let { return it }
        }
        return null
    }

    /** Finds the `<string name="[name]">` value in the module's default `res/values` string files. */
    private suspend fun resolveStringResource(
        app: ExternalApp,
        paths: List<String>,
        moduleRoot: String,
        name: String,
    ): String? {
        val prefix = if (moduleRoot.isEmpty()) {
            "src/main/res/values/"
        } else {
            "$moduleRoot/src/main/res/values/"
        }
        val valueFiles = paths
            .filter { it.startsWith(prefix) && it.endsWith(".xml") && '/' !in it.removePrefix(prefix) }
            .sortedBy { valueFileOrder(it) }
            .take(MAX_VALUE_FILES)
        val regex = Regex(
            """<string\s+name="${Regex.escape(name)}"[^>]*>(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        for (file in valueFiles) {
            val xml = fetchRaw(app, file) ?: continue
            val value = regex.find(xml)?.groupValues?.get(1) ?: continue
            val clean = unescapeAndroidString(value)
            // Ignore a value that's itself a resource reference (rare) rather than show "@string/…".
            if (clean.isBlank() || clean.startsWith("@")) continue
            return clean
        }
        return null
    }

    /** Fetches a repo file's text via the raw CDN (no API rate limit, no base64). Null on failure. */
    private suspend fun fetchRaw(app: ExternalApp, path: String): String? =
        runCatching { getText(rawUrl(app.owner, app.repo, path)) }.getOrNull()

    private fun parseTreePaths(text: String): List<String> =
        json.decodeFromString(TreeResponse.serializer(), text)
            .tree
            .filter { it.type == "blob" }
            .map { it.path }

    /**
     * The project README as HTML, for display on the detail screen. GitHub renders it for us
     * (Accept: application/vnd.github.html); the other providers have no equally simple endpoint, so
     * they return null for now. Returns null on any failure or when there is no README.
     */
    suspend fun readmeHtml(app: ExternalApp): String? = withContext(Dispatchers.IO) {
        runCatching {
            when (app.provider) {
                SourceProvider.GITHUB -> {
                    val response = httpClient.get(
                        "https://api.github.com/repos/${app.owner}/${app.repo}/readme",
                    ) {
                        header("Accept", "application/vnd.github.html")
                        header("X-GitHub-Api-Version", "2022-11-28")
                        githubAuthToken()?.let { header("Authorization", "Bearer $it") }
                    }
                    if (response.status.isSuccess()) response.bodyAsText() else null
                }

                SourceProvider.GITLAB, SourceProvider.CODEBERG -> null
            }
        }.getOrNull()
    }

    /**
     * Fetches the release Droidify should offer for the project: the newest non-draft release that
     * ships an APK this device can install (optionally including pre-releases, and honouring the
     * source's APK name [apkFilter]). Releases with no APK — e.g. a server-only version bump — are
     * skipped, since there's nothing to install from them and their tag would otherwise be mistaken
     * for a new app version. When the newest release carries only foreign-architecture APKs, an older
     * release with a device-compatible one is used instead (graceful fallback). Returns null on
     * network/HTTP/parse failure or when no release in the recent window ships an APK.
     */
    suspend fun latestRelease(
        provider: SourceProvider,
        owner: String,
        repo: String,
        includePrereleases: Boolean = false,
        apkFilter: String? = null,
    ): Release? = withContext(Dispatchers.IO) {
        runCatching {
            when (provider) {
                SourceProvider.GITHUB -> {
                    val text = getText(
                        url = "https://api.github.com/repos/$owner/$repo/releases?per_page=10",
                        github = true,
                    ) ?: return@runCatching null
                    decodeRest(text).filterNot { it.draft }.map { it.toRelease() }
                        .pickInstallable(includePrereleases, apkFilter)
                }

                SourceProvider.CODEBERG -> {
                    val text = getText(
                        url = "https://codeberg.org/api/v1/repos/$owner/$repo/releases?limit=10",
                    ) ?: return@runCatching null
                    decodeRest(text).filterNot { it.draft }.map { it.toRelease() }
                        .pickInstallable(includePrereleases, apkFilter)
                }

                SourceProvider.GITLAB -> {
                    val path = URLEncoder.encode("$owner/$repo", "UTF-8")
                    val text = getText(
                        url = "https://gitlab.com/api/v4/projects/$path/releases?per_page=10",
                    ) ?: return@runCatching null
                    decodeGitlab(text).map { it.toRelease() }
                        .pickInstallable(includePrereleases, apkFilter)
                }
            }
        }.getOrNull()
    }

    private fun decodeRest(text: String): List<RestReleaseDto> =
        json.decodeFromString(ListSerializer(RestReleaseDto.serializer()), text)

    private fun decodeGitlab(text: String): List<GitlabReleaseDto> =
        json.decodeFromString(ListSerializer(GitlabReleaseDto.serializer()), text)

    /**
     * From a newest-first list of releases, picks the newest that ships an APK this device can
     * actually install (honouring [includePrereleases] and the APK name [filter]). If none has a
     * device-compatible APK, falls back to the newest that ships *any* APK, so we degrade to
     * "something installable" rather than nothing. Releases with no APK at all are ignored.
     */
    private fun List<Release>.pickInstallable(
        includePrereleases: Boolean,
        apkFilter: String?,
    ): Release? {
        val candidates = filter { includePrereleases || !it.isPrerelease }
        return candidates.firstOrNull { it.hasCompatibleApk(filter = apkFilter) }
            ?: candidates.firstOrNull { release ->
                release.assets.any { it.name.endsWith(".apk", ignoreCase = true) }
            }
    }

    private suspend fun getText(url: String, github: Boolean = false): String? {
        val response = httpClient.get(url) {
            if (github) {
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
                githubAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
        }
        if (github) {
            // GitHub signals the rate limit with 403/429 and X-RateLimit-Remaining: 0.
            val remaining = response.headers["X-RateLimit-Remaining"]?.toIntOrNull()
            val status = response.status.value
            rateLimited = (status == 403 || status == 429) && remaining == 0
        }
        return if (response.status.isSuccess()) response.bodyAsText() else null
    }

    /** Decodes the base64 `content` field of a GitHub/Gitea "contents" API response into text. */
    private fun decodeContentsBase64(text: String): String? {
        val dto = runCatching {
            json.decodeFromString(ContentsDto.serializer(), text)
        }.getOrNull() ?: return null
        if (dto.encoding != "base64" || dto.content == null) return null
        return runCatching {
            String(Base64.decode(dto.content.replace("\n", ""), Base64.DEFAULT))
        }.getOrNull()
    }

    @Serializable
    private data class ContentsDto(val content: String? = null, val encoding: String? = null)

    @Serializable
    private data class TreeResponse(val tree: List<TreeEntry> = emptyList())

    @Serializable
    private data class TreeEntry(val path: String = "", val type: String = "")

    private companion object {
        /** Where an Android app's `applicationId` usually lives, most likely first. */
        val BUILD_GRADLE_PATHS = listOf(
            "app/build.gradle.kts",
            "app/build.gradle",
            "android/app/build.gradle.kts",
            "android/app/build.gradle",
            "src/app/build.gradle",
        )

        /** `applicationId`, else `namespace`, in either Groovy or Kotlin-DSL form. */
        val PACKAGE_ID_REGEXES = listOf(
            Regex("""applicationId\s*[=(]?\s*["']([\w.]+)["']"""),
            Regex("""namespace\s*[=(]?\s*["']([\w.]+)["']"""),
        )
    }
}

/** How many distinct icon candidates we keep for the picker (one per icon family, best density). */
private const val MAX_ICON_CANDIDATES = 12

private data class ScoredIcon(val path: String, val stem: String, val variant: Int, val density: Int)

/**
 * Ranks raw repo file paths as launcher-icon candidates and returns the best path per icon family
 * (e.g. square / round / foreground), best first. Universal across the common Android conventions:
 * the Fastlane / F-Droid store icon (`metadata/.../images/icon.png`), plus any `mipmap-*` or
 * `drawable-*` resource directory, raster PNG/WebP only (adaptive/vector .xml is skipped — it can't be
 * shown as a plain image), and the usual launcher names (`ic_launcher`, `icon`, `launcher`,
 * `app_icon`…) at any path depth, so Flutter/multi-module repos work too. The store icon is the only
 * raster many modern apps ship (their launcher icon being pure adaptive/vector), so it doubles as the
 * fallback for those.
 */
private fun rankIconPaths(paths: List<String>): List<String> {
    val candidates = mutableListOf<ScoredIcon>()

    // The Fastlane / F-Droid store icon: a full-res composed raster. Scored as a composed square (so it
    // beats a transparent foreground) at a high — but not maximal — density, so a real high-density
    // mipmap icon still wins when present, while vector-only apps fall back to this. Prefer en-US.
    paths.filter { isStoreIcon(it) }.minByOrNull { storeIconOrder(it) }?.let {
        candidates += ScoredIcon(path = it, stem = "@store", variant = 4, density = 5)
    }

    // Launcher icons from res/: keep the highest density of each named icon family.
    paths.mapNotNull { path ->
        val file = path.substringAfterLast('/')
        val ext = file.substringAfterLast('.', "").lowercase()
        if (ext != "png" && ext != "webp") return@mapNotNull null
        val dir = path.substringBeforeLast('/', "").substringAfterLast('/').lowercase()
        if (!dir.startsWith("mipmap") && !dir.startsWith("drawable")) return@mapNotNull null
        val stem = file.substringBeforeLast('.').lowercase()
        val variant = iconVariantRank(stem) ?: return@mapNotNull null
        ScoredIcon(path, stem, variant, densityRank(dir))
    }
        .groupBy { it.stem }
        .forEach { (_, sameStem) -> candidates += sameStem.maxByOrNull { it.density }!! }

    // Order by variant (square > round > foreground), then density.
    return candidates
        .sortedWith(compareByDescending<ScoredIcon> { it.variant }.thenByDescending { it.density })
        .map { it.path }
        .distinct()
        .take(MAX_ICON_CANDIDATES)
}

/** A Fastlane / F-Droid store-listing icon (`…/metadata/…/images/icon.png`). */
private fun isStoreIcon(path: String): Boolean {
    val lower = path.lowercase()
    return "metadata" in lower &&
        (lower.endsWith("/images/icon.png") ||
            lower.endsWith("/images/icon.webp") ||
            lower.endsWith("/images/icon.jpg"))
}

/** Prefer the English store icon when a repo ships per-locale copies (they're the same image). */
private fun storeIconOrder(path: String): Int {
    val lower = path.lowercase()
    return when {
        "/en-us/" in lower -> 0
        "/en/" in lower -> 1
        else -> 2
    }
}

/**
 * Classifies an icon file-name stem into a launcher-icon variant rank, or null when it isn't a
 * launcher icon. Higher = more preferred: a plain square launcher icon outranks the round one, which
 * outranks the (often transparent) adaptive foreground, which outranks other launcher-named icons.
 */
private fun iconVariantRank(stem: String): Int? {
    // Adaptive backgrounds, monochrome/themed glyphs and unrelated assets aren't usable app icons.
    val excluded = listOf(
        "background", "monochrome", "notification", "splash", "banner", "feature", "badge", "store",
    )
    if (excluded.any { stem.contains(it) }) return null
    val launcherish = stem.startsWith("ic_launcher") || stem.startsWith("icon") ||
        stem.startsWith("appicon") || stem.startsWith("app_icon") || stem.contains("launcher")
    if (!launcherish) return null
    // A "_foreground"/"_round" suffix marks an adaptive component; any other launcher-ish base name
    // (ic_launcher, launcher_icon, icon…) is the fully-composed square icon and is preferred — so a
    // transparent foreground never outranks the real icon, whatever the project names it.
    return when {
        stem.endsWith("_foreground") -> 2
        stem.endsWith("_round") -> 3
        else -> 4
    }
}

/** Density preference of a `mipmap-*` / `drawable-*` directory; higher density = sharper icon. */
private fun densityRank(dir: String): Int = when {
    dir.contains("xxxhdpi") -> 6
    dir.contains("xxhdpi") -> 5
    dir.contains("xhdpi") -> 4
    dir.contains("hdpi") -> 3
    dir.contains("mdpi") -> 2
    dir.contains("ldpi") -> 1
    else -> 0
}

/** A raw, always-current URL for a file in a GitHub repo (HEAD = the default branch). */
private fun rawUrl(owner: String, repo: String, path: String): String =
    "https://raw.githubusercontent.com/$owner/$repo/HEAD/$path"

/** Icon candidates + the real app name detected from a source repo, shown before install. */
data class RepoMetadata(
    val iconCandidates: List<String> = emptyList(),
    val appName: String? = null,
)

/** How many manifest / value files we'll fetch while resolving the app name, to bound network use. */
private const val MAX_MANIFESTS = 8
private const val MAX_VALUE_FILES = 12

/** Module directory names that commonly hold the launcher app in a multi-module repo, tried early. */
private val APP_MODULE_HINTS = listOf("presentation", "mobile", "application", "android-app", "androidApp")

/**
 * The `AndroidManifest.xml` paths worth checking for the app name, most likely the app module first.
 * Main source set only (no `build/`, `androidTest/`, `test/` variants).
 */
private fun pickManifestPaths(paths: List<String>): List<String> =
    paths.asSequence()
        .filter {
            it.endsWith("/src/main/AndroidManifest.xml") &&
                "/build/" !in it && "/androidTest/" !in it && "/test/" !in it
        }
        .sortedBy { manifestOrder(it) }
        .take(MAX_MANIFESTS)
        .toList()

private fun manifestOrder(path: String): Int = when {
    path == "app/src/main/AndroidManifest.xml" -> 0
    path == "android/app/src/main/AndroidManifest.xml" -> 1
    path.startsWith("app/") -> 2
    path.startsWith("android/app/") -> 3
    APP_MODULE_HINTS.any { path.startsWith("$it/") } -> 4
    // Otherwise prefer the shallowest module (fewer path segments).
    else -> 5 + path.count { it == '/' }
}

/**
 * Pulls the `<application android:label="…">` value out of a manifest, ignoring the `android:label`s
 * on `<permission>` / `<activity>` elements (which aren't the app name). Returns null when the
 * application element has no label (e.g. a library module's manifest).
 */
private fun extractApplicationLabel(xml: String): String? {
    val applicationTag = Regex("""<application\b[^>]*>""").find(xml)?.value ?: return null
    return Regex("""\bandroid:label\s*=\s*"([^"]+)"""")
        .find(applicationTag)
        ?.groupValues
        ?.get(1)
}

/** Orders a module's value files so the app name is found fast: strings.xml, the conventional
 *  non-translatable file (where app_name often lives), other string files, then the rest. */
private fun valueFileOrder(path: String): Int {
    val name = path.substringAfterLast('/')
    return when {
        name == "strings.xml" -> 0
        "donottranslate" in name || "dnt" in name -> 1
        name.startsWith("strings") -> 2
        "app" in name -> 3
        else -> 4
    }
}

/** Turns an Android string-resource value into plain display text (unescapes quotes/entities). */
private fun unescapeAndroidString(raw: String): String {
    var s = raw.trim()
    // A double-quoted value preserves leading/trailing spaces; drop the wrapping quotes.
    if (s.length >= 2 && s.first() == '"' && s.last() == '"') s = s.substring(1, s.length - 1)
    return s
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\n", " ")
        .replace("\\\\", "\\")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#160;", " ")
        .trim()
}

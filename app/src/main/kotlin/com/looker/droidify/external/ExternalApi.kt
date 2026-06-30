package com.looker.droidify.external

import android.util.Base64
import com.looker.droidify.datastore.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
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
        latestRelease(
            app.provider,
            app.effectiveHost,
            app.owner,
            app.repo,
            app.includePrereleases,
            app.apkFilter,
        )

    /** Probes whether [host] runs Gitea/Forgejo by hitting its repo API. Lets a pasted URL whose host
     *  isn't a known public provider be recognised as a self-hosted instance. Never throws. */
    suspend fun isGiteaInstance(host: String, owner: String, repo: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { getText("https://$host/api/v1/repos/$owner/$repo") }.getOrNull() != null
        }

    /**
     * Best-effort application id of the app a source builds, read from its `build.gradle`'s
     * `applicationId` (falling back to `namespace`). Knowing the package id lets an already-installed app
     * be matched, so its real on-device name and icon show before the user ever installs through us.
     * Works for every provider via the raw file base. Returns null when no build file or id is found.
     * Never throws.
     */
    suspend fun fetchPackageId(app: ExternalApp): String? = withContext(Dispatchers.IO) {
        for (path in BUILD_GRADLE_PATHS) {
            val source = fetchRaw(app, path) ?: continue
            for (regex in PACKAGE_ID_REGEXES) {
                regex.find(source)?.let { return@withContext it.groupValues[1] }
            }
        }
        null
    }

    /**
     * Best-effort launcher icons for the app, found in the source repo itself so the card can show the
     * real icon before anything is installed. Reads the repo's full file tree
     * in a single request and returns the matching raster launcher icons (PNG/WebP) as raw URLs, best
     * first: highest-density square icon, then round, then adaptive-foreground, then other
     * launcher-named icons. Adaptive/vector (.xml) icons are skipped — they can't be rendered as a plain
     * image. Works for every provider; an empty list falls back to the account avatar / placeholder.
     * Never throws.
     */
    suspend fun fetchIconCandidates(app: ExternalApp): List<String> = withContext(Dispatchers.IO) {
        val paths = fetchTreePaths(app)
        rankIconPaths(paths).map { app.readmeBaseUrl + it }
    }

    /**
     * Repo metadata used to present an external app *before* it's installed: launcher-icon candidates
     * and the app's real user-facing name. A single repo-tree request drives both (the name then needs
     * the manifest + string file). Works for every provider. Never throws.
     */
    suspend fun fetchRepoMetadata(app: ExternalApp): RepoMetadata? = withContext(Dispatchers.IO) {
        // Null (not an empty result) when the repo tree couldn't be read, so the caller can retry later
        // instead of caching "nothing found" / "not a TV app" from a transient failure.
        val paths = fetchTreePaths(app)
        if (paths.isEmpty()) return@withContext null
        RepoMetadata(
            iconCandidates = rankIconPaths(paths).map { app.readmeBaseUrl + it },
            appName = resolveAppName(app, paths),
            supportsTelevision = detectTelevisionSupport(app, paths),
        )
    }

    /**
     * Whether the source repo is built for Android TV, read straight from its manifest(s) — no APK
     * download. A TV app declares either the leanback launcher category on an activity
     * (`android.intent.category.LEANBACK_LAUNCHER`) or the leanback uses-feature
     * (`android.software.leanback`); finding either in any of the app-module manifests is the signal.
     * Returns false when no manifest resolves (then it simply isn't shown in the "Made for TV" row).
     */
    private suspend fun detectTelevisionSupport(app: ExternalApp, paths: List<String>): Boolean {
        for (manifestPath in pickManifestPaths(paths)) {
            val xml = fetchRaw(app, manifestPath) ?: continue
            if (xml.contains("android.software.leanback", ignoreCase = true) ||
                xml.contains("LEANBACK_LAUNCHER", ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    /** The repo's whole file tree (blob paths), or empty on any failure. Each provider exposes a
     *  recursive tree API; GitHub and Gitea/Forgejo share the same `{tree:[…]}` shape, GitLab returns a
     *  paged array. */
    private suspend fun fetchTreePaths(app: ExternalApp): List<String> = when (app.provider) {
        SourceProvider.GITHUB -> {
            val url = "https://api.github.com/repos/${app.owner}/${app.repo}/git/trees/HEAD?recursive=1"
            val text = runCatching { getText(url, github = true) }.getOrNull() ?: return emptyList()
            runCatching { parseTreePaths(text) }.getOrNull() ?: emptyList()
        }

        SourceProvider.CODEBERG -> {
            val url = "https://${app.effectiveHost}/api/v1/repos/${app.owner}/${app.repo}" +
                "/git/trees/HEAD?recursive=true&per_page=1000"
            val text = runCatching { getText(url) }.getOrNull() ?: return emptyList()
            runCatching { parseTreePaths(text) }.getOrNull() ?: emptyList()
        }

        SourceProvider.GITLAB -> fetchGitlabTreePaths(app)
    }

    /** GitLab's tree API is a paged bare array (max 100/page); walk a bounded number of pages. */
    private suspend fun fetchGitlabTreePaths(app: ExternalApp): List<String> {
        val encoded = URLEncoder.encode("${app.owner}/${app.repo}", "UTF-8")
        val paths = mutableListOf<String>()
        for (page in 1..GITLAB_TREE_MAX_PAGES) {
            val url = "https://${app.effectiveHost}/api/v4/projects/$encoded/repository/tree" +
                "?recursive=true&ref=HEAD&per_page=100&page=$page"
            val text = runCatching { getText(url) }.getOrNull() ?: break
            val batch = runCatching {
                json.decodeFromString(ListSerializer(TreeEntry.serializer()), text)
            }.getOrNull().orEmpty()
            batch.forEach { if (it.type == "blob") paths += it.path }
            if (batch.size < 100) break
        }
        return paths
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

    /** Fetches a repo file's text via the provider's branchless raw base ([ExternalApp.readmeBaseUrl]).
     *  For GitHub that's the rate-limit-free CDN; for Gitea/GitLab the raw endpoint. Null on failure. */
    private suspend fun fetchRaw(app: ExternalApp, path: String): String? =
        runCatching { getText(app.readmeBaseUrl + path) }.getOrNull()

    private fun parseTreePaths(text: String): List<String> =
        json.decodeFromString(TreeResponse.serializer(), text)
            .tree
            .filter { it.type == "blob" }
            .map { it.path }

    /**
     * The project README as HTML, for display on the detail screen. GitHub renders it for us
     * (Accept: application/vnd.github.html); for Gitea/Forgejo and GitLab the raw Markdown is fetched
     * and rendered locally ([renderMarkdownToHtml]). Returns null on any failure or when there is no
     * README.
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

                // Gitea/Forgejo (codeberg.org + self-hosted) and GitLab have no free rendered-HTML
                // README endpoint, so fetch the raw Markdown and render it locally. Their raw endpoints
                // content-negotiate (serving an HTML page to the WebView's requests, not the file), so
                // the README's own relative images are fetched here and inlined as data URIs.
                SourceProvider.CODEBERG, SourceProvider.GITLAB -> {
                    val markdown = fetchRawReadme(app) ?: return@runCatching null
                    inlineRelativeImages(renderMarkdownToHtml(markdown), app)
                }
            }
        }.getOrNull()
    }

    /** Fetches a project's raw README Markdown by trying the common file names against the provider's
     *  branchless raw base ([ExternalApp.readmeBaseUrl]). Null when none is found. */
    private suspend fun fetchRawReadme(app: ExternalApp): String? {
        for (name in README_NAMES) {
            runCatching { getText(app.readmeBaseUrl + name) }.getOrNull()?.let { return it }
        }
        return null
    }

    /**
     * Replaces relative `<img>` sources in rendered README HTML with `data:` URIs of the actual image
     * bytes. Gitea/GitLab raw endpoints decide between the file and an HTML viewer page from request
     * headers the WebView doesn't send for sub-resources, so a plain relative URL would load the HTML
     * page instead of the image. Fetching here (a non-browser client gets the real bytes) sidesteps that.
     * Absolute URLs (e.g. shields.io badges) are left untouched, as are images that fail or are too big.
     */
    private suspend fun inlineRelativeImages(html: String, app: ExternalApp): String {
        val relativeSrcs = IMG_SRC_REGEX.findAll(html)
            .map { it.groupValues[1] }
            .filterNot { it.startsWith("http://", true) || it.startsWith("https://", true) ||
                it.startsWith("//") || it.startsWith("data:", true) }
            .distinct()
            .toList()
        if (relativeSrcs.isEmpty()) return html
        val replacements = HashMap<String, String>()
        for (src in relativeSrcs) {
            val path = src.removePrefix("./").substringBefore('?').substringBefore('#')
            val dataUri = fetchImageAsDataUri(app.readmeBaseUrl + path) ?: continue
            replacements[src] = dataUri
        }
        if (replacements.isEmpty()) return html
        return IMG_SRC_REGEX.replace(html) { match ->
            val dataUri = replacements[match.groupValues[1]] ?: return@replace match.value
            match.value.replace("src=\"${match.groupValues[1]}\"", "src=\"$dataUri\"")
        }
    }

    /** Downloads an image and returns it as a `data:` URI, or null on failure / non-image / oversize. */
    private suspend fun fetchImageAsDataUri(url: String): String? {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) return null
        val contentType = response.headers["Content-Type"]?.substringBefore(';')?.trim().orEmpty()
        if (!contentType.startsWith("image/")) return null
        response.headers["Content-Length"]?.toLongOrNull()?.let {
            if (it > MAX_INLINE_IMAGE_BYTES) return null
        }
        val bytes: ByteArray = response.body()
        if (bytes.size > MAX_INLINE_IMAGE_BYTES) return null
        return "data:$contentType;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
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
        host: String,
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

                // Gitea/Forgejo: codeberg.org or any self-hosted instance (same REST shape).
                SourceProvider.CODEBERG -> {
                    val text = getText(
                        url = "https://$host/api/v1/repos/$owner/$repo/releases?limit=10",
                    ) ?: return@runCatching null
                    decodeRest(text).filterNot { it.draft }.map { it.toRelease() }
                        .pickInstallable(includePrereleases, apkFilter)
                }

                SourceProvider.GITLAB -> {
                    val path = URLEncoder.encode("$owner/$repo", "UTF-8")
                    val text = getText(
                        url = "https://$host/api/v4/projects/$path/releases?per_page=10",
                    ) ?: return@runCatching null
                    decodeGitlab(text).map { it.toRelease() }
                        .pickInstallable(includePrereleases, apkFilter)
                }
            }
        }.getOrNull()
    }

    /**
     * Lists the repos of a whole account [owner] (a user or org) on [provider]/[host], always skipping
     * archived repos and, unless [includeForks], forks too. Used by the account-source feature; the
     * caller then keeps only the repos that actually ship an installable APK release. Forks can't be
     * detected on GitLab (its project list doesn't flag them), so [includeForks] has no effect there.
     * Paged, with a bounded page count. Never throws.
     */
    suspend fun listAccountRepos(
        provider: SourceProvider,
        host: String,
        owner: String,
        includeForks: Boolean,
    ): List<RepoRef> = withContext(Dispatchers.IO) {
        runCatching {
            when (provider) {
                SourceProvider.GITHUB -> pagedAccountRepos { page ->
                    val text = getText(
                        url = "https://api.github.com/users/$owner/repos" +
                            "?per_page=100&page=$page&type=owner&sort=pushed",
                        github = true,
                    ) ?: return@pagedAccountRepos PageResult(emptyList(), 0)
                    giteaPage(text, owner, includeForks)
                }

                SourceProvider.CODEBERG -> pagedAccountRepos { page ->
                    val text = getText(
                        url = "https://$host/api/v1/users/$owner/repos?limit=50&page=$page",
                    ) ?: return@pagedAccountRepos PageResult(emptyList(), 0)
                    giteaPage(text, owner, includeForks)
                }

                SourceProvider.GITLAB -> {
                    // A GitLab account name can be a user or a group; try user projects first, then group
                    // projects (including subgroups) when that yields nothing.
                    val user = pagedAccountRepos { page ->
                        gitlabProjects("https://$host/api/v4/users/$owner/projects?per_page=100&page=$page")
                    }
                    user.ifEmpty {
                        pagedAccountRepos { page ->
                            gitlabProjects(
                                "https://$host/api/v4/groups/$owner/projects" +
                                    "?per_page=100&page=$page&include_subgroups=true",
                            )
                        }
                    }
                }
            }
        }.getOrNull().orEmpty()
    }

    /** One page of an account-repo listing: the kept refs plus the raw item count (before filtering),
     *  so pagination can stop at the first genuinely empty page without a filtered page (all forks)
     *  cutting it short. */
    private data class PageResult(val refs: List<RepoRef>, val rawCount: Int)

    /** Walks pages (max [ACCOUNT_REPOS_MAX_PAGES]) until a page comes back empty. */
    private inline fun pagedAccountRepos(fetch: (page: Int) -> PageResult): List<RepoRef> {
        val all = mutableListOf<RepoRef>()
        for (page in 1..ACCOUNT_REPOS_MAX_PAGES) {
            val result = fetch(page)
            all += result.refs
            if (result.rawCount == 0) break
        }
        return all
    }

    private fun giteaPage(text: String, fallbackOwner: String, includeForks: Boolean): PageResult {
        val dtos = json.decodeFromString(ListSerializer(GiteaRepoDto.serializer()), text)
        // Archived repos (explicitly retired by the owner) are always skipped; forks are skipped unless
        // the user opted to include them (some publish their apps as forks of upstream projects). The
        // release-APK check by the caller is the final filter.
        val refs = dtos.filterNot { it.archived || (!includeForks && it.fork) }
            .map { RepoRef(it.owner.login.ifEmpty { fallbackOwner }, it.name) }
        return PageResult(refs, dtos.size)
    }

    private suspend fun gitlabProjects(url: String): PageResult {
        val text = getText(url) ?: return PageResult(emptyList(), 0)
        val dtos = runCatching {
            json.decodeFromString(ListSerializer(GitlabProjectDto.serializer()), text)
        }.getOrNull().orEmpty()
        val refs = dtos.mapNotNull { project ->
            val full = project.pathWithNamespace.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val repo = full.substringAfterLast('/')
            val owner = full.substringBeforeLast('/', "")
            if (owner.isBlank() || repo.isBlank()) null else RepoRef(owner, repo)
        }
        return PageResult(refs, dtos.size)
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

    @Serializable
    private data class TreeResponse(val tree: List<TreeEntry> = emptyList())

    @Serializable
    private data class TreeEntry(val path: String = "", val type: String = "")

    /** Minimal repo shape from the GitHub/Gitea "list account repos" endpoints. */
    @Serializable
    private data class GiteaRepoDto(
        val name: String = "",
        val fork: Boolean = false,
        val archived: Boolean = false,
        val owner: OwnerLoginDto = OwnerLoginDto(),
    )

    @Serializable
    private data class OwnerLoginDto(val login: String = "")

    /** Minimal project shape from the GitLab "list projects" endpoints. */
    @Serializable
    private data class GitlabProjectDto(
        @SerialName("path_with_namespace") val pathWithNamespace: String = "",
    )

    private companion object {
        /** Page cap when listing an account's repos, so a huge account can't spin forever. */
        const val ACCOUNT_REPOS_MAX_PAGES = 5

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

/** An `owner/repo` pair returned when listing a whole account's repositories. */
data class RepoRef(val owner: String, val repo: String)

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

/** How many 100-item pages of GitLab's tree API to walk while looking for the manifest / icons. */
private const val GITLAB_TREE_MAX_PAGES = 10

/** Largest README image inlined as a data URI; bigger ones are left as-is to avoid bloating the HTML. */
private const val MAX_INLINE_IMAGE_BYTES = 1_000_000

/** Captures the `src` of an `<img>` tag (CommonMark output and any raw HTML in the README). */
private val IMG_SRC_REGEX = Regex("""<img\b[^>]*?\bsrc="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)

/** README file names tried in order against a repo's branchless raw base. */
private val README_NAMES = listOf(
    "README.md", "readme.md", "Readme.md", "README.markdown", "README.MD", "README", "readme",
)

/** GitHub-flavoured Markdown extensions: tables, strikethrough, autolinks and task lists. */
private val MARKDOWN_EXTENSIONS = listOf(
    TablesExtension.create(),
    StrikethroughExtension.create(),
    AutolinkExtension.create(),
    TaskListItemsExtension.create(),
)
private val markdownParser: Parser = Parser.builder().extensions(MARKDOWN_EXTENSIONS).build()
private val markdownRenderer: HtmlRenderer =
    HtmlRenderer.builder().extensions(MARKDOWN_EXTENSIONS).build()

/** Renders Markdown to an HTML fragment locally (no network, no external service). Relative links and
 *  images stay relative so the WebView resolves them against the repo's raw base. Raw HTML in the
 *  source is passed through (as GitHub does); the WebView runs no JavaScript, so it stays inert. */
private fun renderMarkdownToHtml(markdown: String): String =
    markdownRenderer.render(markdownParser.parse(markdown))

/** Icon candidates + the real app name detected from a source repo, shown before install, plus whether
 *  the repo's manifest declares Android TV support. */
data class RepoMetadata(
    val iconCandidates: List<String> = emptyList(),
    val appName: String? = null,
    val supportsTelevision: Boolean = false,
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

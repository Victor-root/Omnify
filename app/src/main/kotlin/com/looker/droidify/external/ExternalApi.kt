package com.looker.droidify.external

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.utility.common.LanguageDetector
import com.looker.droidify.utility.common.withoutNonLocalePrefix
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
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

    private val _rateLimitRemaining = MutableStateFlow<Int?>(null)

    /** Remaining anonymous GitHub API quota for the current hour, as last reported by GitHub itself
     *  (the `X-RateLimit-Remaining` header, present on every api.github.com response, not just failed
     *  ones). Null until the first such call this session. Lets the UI warn while the budget is running
     *  low, instead of only after it's already exhausted. */
    val rateLimitRemaining: StateFlow<Int?> = _rateLimitRemaining.asStateFlow()

    /** Consecutive api.github.com calls rejected with HTTP 401 Bad credentials, across every source.
     *  Only a run of these — not a single one — latches [githubTokenInvalid], so one fluke rejection
     *  can't raise a false alarm: a network failure never reaches this check at all (getText's caller
     *  catches and returns before a status code even exists), and any other outcome (a genuine success,
     *  a 404, a rate limit) leaves the streak untouched rather than resetting it, so an unrelated blip
     *  in between two real 401s can't hide them either. A token that's actually expired or revoked fails
     *  every call the same way, so the real case still confirms itself within moments. */
    private var unauthorizedStreak = 0

    private val _githubTokenInvalid = MutableStateFlow(false)

    /** True once [unauthorizedStreak] confirms the configured GitHub token is being rejected outright —
     *  the moment to tell the user to replace it in Settings (expired, revoked, or simply mistyped).
     *  Distinct from [rateLimited]: GitHub answers 401 only when a token was actually sent and rejected,
     *  never for an anonymous request (that's rate-limited instead, at worst), so this can't be confused
     *  with "no token configured," and it can only ever be set from a real response GitHub sent back —
     *  never from a connection failure. Cleared by the next call that succeeds with the current token, so
     *  the signal always reflects its current validity rather than staying latched after it's fixed. */
    val githubTokenInvalid: StateFlow<Boolean> = _githubTokenInvalid.asStateFlow()

    /** The user's optional GitHub token, or null when unset. Sent only to api.github.com requests to
     *  lift the anonymous 60-requests/hour rate limit to 5000. */
    private suspend fun githubAuthToken(): String? =
        settingsRepository.getInitial().githubToken.trim().takeIf { it.isNotEmpty() }

    /** True when the last GitHub call was rate-limited *and* no token is configured — i.e. the moment
     *  to nudge the user that adding a token would lift the limit. */
    suspend fun shouldSuggestGithubToken(): Boolean = rateLimited && githubAuthToken() == null

    /**
     * Checks the currently configured GitHub token right now, instead of leaving [githubTokenInvalid]
     * to whatever it was until some unrelated call happens to touch api.github.com next (a background
     * refresh that may not run for a while, or a stale value inherited from before the token changed).
     * Meant to be called right after the user saves a new token, or one is restored from a backup, so
     * the warning banner and the verified checkmark react immediately: cleared the moment it's
     * confirmed good, or, since a single call can't yet distinguish "genuinely still bad" from "hasn't
     * reached the streak threshold" (see [unauthorizedStreak]), fires exactly [TOKEN_INVALID_STREAK]
     * requests so a still-bad token is just as conclusively confirmed within this one call as a fixed
     * one is. Hits GitHub's own rate-limit endpoint: real credentials validation with no side effect,
     * and (unlike almost every other GitHub REST call) it doesn't itself count against the very quota
     * it reports. Never throws, so a caller with no network right now (e.g. mid-restore, where the
     * rest of a backup must still apply regardless) is never blocked or failed by this.
     */
    suspend fun verifyGithubToken() = withContext(Dispatchers.IO) {
        repeat(TOKEN_INVALID_STREAK) {
            runCatching { getText(GITHUB_RATE_LIMIT_URL, github = true) }
        }
    }

    suspend fun latestReleaseFor(app: ExternalApp): Release? =
        latestRelease(
            app.provider,
            app.effectiveHost,
            app.owner,
            app.repo,
            app.includePrereleases,
            app.apkFilter,
            app.versionExcludeFilter,
        )

    /**
     * Same lookup as [latestReleaseFor], but reports *why* nothing was found instead of collapsing every
     * failure to null — used by the install/update flow (the one place the reason reaches the user as a
     * message) so it can distinguish a genuine network failure from "every recent release is a
     * pre-release and this source excludes them" or "none ships a compatible APK", which otherwise all
     * looked identical (a generic "couldn't reach GitHub") even though only one of them actually is that.
     */
    suspend fun latestReleaseLookup(app: ExternalApp): ReleaseLookup = withContext(Dispatchers.IO) {
        val releases = runCatching {
            fetchReleases(
                app.provider, app.effectiveHost, app.owner, app.repo,
                minQualifying = 1,
                isQualifying = { it.isAllowedBy(app.includePrereleases, app.versionExcludeFilter) },
            )
        }.getOrNull()
        val picked = releases?.pickInstallable(app.includePrereleases, app.apkFilter, app.versionExcludeFilter)
        // Only the pre-release setting applied, kept separate from `picked` so a source whose exclude
        // filter (not the pre-release setting) is what's disqualifying everything gets its own accurate
        // reason below instead of the generic NoCompatibleApk.
        val nonPrerelease = releases?.filter { app.includePrereleases || !it.isPrerelease }
        when {
            picked != null -> ReleaseLookup.Found(picked)
            releases == null -> ReleaseLookup.FetchFailed
            releases.isNotEmpty() && releases.all { it.isPrerelease } && !app.includePrereleases ->
                ReleaseLookup.OnlyPrereleasesExcluded
            !nonPrerelease.isNullOrEmpty() &&
                nonPrerelease.all { it.matchesExcludeFilter(app.versionExcludeFilter) } ->
                ReleaseLookup.AllExcludedByFilter
            else -> ReleaseLookup.NoCompatibleApk
        }
    }

    /**
     * Up to [limit] non-draft releases within the recent window (newest first) that ship at least one
     * APK this source's [ExternalApp.apkFilter] would accept: the external-app equivalent of the
     * F-Droid catalogue's version list, so the user can pick a specific past version to install
     * instead of only ever the one [latestReleaseFor] would offer. Unlike that function, nothing
     * is filtered by device-ABI compatibility or pre-release status here; the caller decides what
     * to show. Empty on network/HTTP/parse failure.
     *
     * [limit] both bounds how far pagination walks (nothing is gathered beyond what answers it) and
     * caps what's returned, so a caller asking for a handful of rows to show before a "show more" tap
     * gets back exactly that, not however many happened to share the same fetched page: a repo with
     * frequent releases can otherwise fill an entire page (see [RELEASES_PER_PAGE]) that this then has
     * to hold and the caller discards all but a handful of.
     */
    suspend fun releaseHistory(
        app: ExternalApp,
        limit: Int = RELEASE_HISTORY_TARGET,
    ): List<Release> = withContext(Dispatchers.IO) {
        runCatching {
            fetchReleases(
                app.provider, app.effectiveHost, app.owner, app.repo,
                minQualifying = limit,
                isQualifying = {
                    it.isAllowedBy(app.includePrereleases, app.versionExcludeFilter) &&
                        it.hasApkMatchingFilter(app.apkFilter)
                },
            )
        }
            .getOrNull()
            .orEmpty()
            // Shares pickInstallable's own criteria: a release the source excludes shouldn't appear in
            // the version list either: the list should only ever offer what could actually be
            // installed from it.
            .allowedBy(app.includePrereleases, app.versionExcludeFilter)
            // Strict, not selectApkAsset's own mercy fallback (see hasApkMatchingFilter's doc
            // comment): otherwise a monorepo publishing more than one app's releases (e.g.
            // bitwarden/android) would list every release regardless of which app it belongs to,
            // since selectApkAsset always returns *something* once a release has any APK at all.
            .filter { it.hasApkMatchingFilter(app.apkFilter) }
            .take(limit)
    }

    /** Probes whether [host] runs Gitea/Forgejo by hitting its repo API. Lets a pasted URL whose host
     *  isn't a known public provider be recognised as a self-hosted instance. Never throws. */
    suspend fun isGiteaInstance(host: String, owner: String, repo: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { getText("https://$host/api/v1/repos/${repoPath(owner, repo)}") }.getOrNull() != null
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
    suspend fun fetchRepoMetadata(
        app: ExternalApp,
        // Composing it costs a few extra requests (the definition plus each of its layers), so a caller
        // that already has this source's icon settled asks for the rest without it.
        includeAdaptiveIcon: Boolean = true,
    ): RepoMetadata? = withContext(Dispatchers.IO) {
        // Null (not an empty result) when the repo tree couldn't be read, so the caller can retry later
        // instead of caching "nothing found" / "not a TV app" from a transient failure.
        val paths = fetchTreePaths(app)
        if (paths.isEmpty()) return@withContext null
        RepoMetadata(
            iconCandidates = rankIconPaths(paths).map { app.readmeBaseUrl + it },
            adaptiveIcon = if (includeAdaptiveIcon) composeAdaptiveIcon(app, paths) else null,
            appName = resolveAppName(app, paths),
            supportsTelevision = detectTelevisionSupport(app, paths),
        )
    }

    /**
     * The icon Android will really draw for [app], built from the `<adaptive-icon>` in its repository.
     * Null when it has none, or on Android 7 and below where the flat raster genuinely is what the
     * device uses. See [AdaptiveIconComposer] for why the raster alone isn't good enough.
     */
    private suspend fun composeAdaptiveIcon(app: ExternalApp, paths: List<String>): Bitmap? =
        AdaptiveIconComposer(
            readFile = { path -> getText(app.readmeBaseUrl + path) },
            readBytes = { path -> fetchBytes(app.readmeBaseUrl + path) },
        ).compose(paths)

    /** Downloads a repo file as raw bytes, bounded like every other fetch here. Null on any failure. */
    private suspend fun fetchBytes(url: String): ByteArray? = runCatching {
        val response = httpClient.get(url)
        if (response.status.isSuccess()) response.bodyBytesAtMost(MAX_INLINE_IMAGE_BYTES) else null
    }.getOrNull()

    /**
     * The app's real supported languages, read directly from the source repo's Android resource
     * directories (`res/values-xx/`, `res/values-b+sr+Latn/`, …) — the same folders `aapt` compiles
     * into the per-locale resource configs [com.looker.droidify.utility.apk.RemoteApkLocaleReader]
     * reads back out of a *built* APK. Reading the source directly sidesteps every way that a release
     * build/download can go wrong (a host that mishandles range requests, a build that trims
     * resources, …), at the cost of only counting a plain locale-qualifier folder — a combined one
     * (`values-fr-v21`) is rare for a translation-only folder and skipped rather than mis-parsed.
     * Null when the repo tree couldn't be read at all; an empty (non-null) list is a genuine "no
     * locale-specific resource folder found" answer (e.g. translations delivered some other way this
     * can't see). Never throws.
     */
    suspend fun fetchSourceLocales(app: ExternalApp): List<String>? = withContext(Dispatchers.IO) {
        val paths = fetchTreePaths(app)
        if (paths.isEmpty()) return@withContext null
        val androidLocales = paths.mapNotNull { localeFromResValuesPath(it) }
        // The unqualified res/values/ (no "-xx" suffix at all) is the base/default strings — by
        // Android's near-universal convention, written in English, with values-xx/ only for the
        // *other* languages layered on top. Without this, the language the app was actually written in
        // never showed up at all, only the translations on top of it.
        val hasDefaultValues = paths.any { RES_DEFAULT_VALUES_REGEX.containsMatchIn(it) }
        // A cross-platform (Flutter, React Native, …) app has no res/values-xx/ at all — its UI strings
        // are its own asset files, not Android resources — so this is tried too, whether or not the
        // first found anything, and the two are merged (a project can plausibly use both for different
        // parts of the app).
        val i18nLocales = paths.mapNotNull { localeFromI18nAssetPath(it) }
        // A Kotlin Multiplatform app using moko-resources (see localeFromMokoResourcesPath) — its own,
        // third convention, tried unconditionally like the other two.
        val mokoLocales = paths.mapNotNull { localeFromMokoResourcesPath(it) }
        (androidLocales + i18nLocales + mokoLocales + listOfNotNull("en".takeIf { hasDefaultValues }))
            .distinct()
            .sorted()
    }

    /**
     * Best-effort ISO language code of the app's own base/default UI strings — read straight from the
     * source repo's unqualified `res/values/` (or `composeResources/values/`) string files and
     * language-detected ([LanguageDetector]). Android's unqualified default resource config carries no
     * language tag, so a MONOLINGUAL app authored in a language other than English is otherwise reported
     * as English purely by convention (see [com.looker.droidify.utility.apk.ApkResourceLocales]'s
     * DEFAULT_LOCALE); this is what tells "written in French" apart from "written in English" for such an
     * app, so it can be named correctly instead of mislabelled English. Null when the base strings can't
     * be read, aren't in `res/values/`, or the language can't be told confidently. Never throws.
     */
    suspend fun detectBaseLanguage(app: ExternalApp): String? = withContext(Dispatchers.IO) {
        val paths = fetchTreePaths(app)
        if (paths.isEmpty()) return@withContext null
        // The classic case first: base strings declared in res/values/ XML. When that carries nothing
        // detectable — a Compose-first app whose only res/values/ string is its app name — fall back to
        // the app's own Kotlin UI source, where such an app writes its visible text instead.
        detectLanguageFromValuesXml(app, paths)
            ?: detectLanguageFromUiSource(app, paths)
    }

    /**
     * Language of the base strings declared in the app's unqualified `res/values/` (or
     * `composeResources/values/`) XML — the classic Android string-resource case. Null when there are
     * no such files, or too little text to tell (e.g. a Compose-only app whose `res/values/strings.xml`
     * holds nothing but the app name — see [detectLanguageFromUiSource]).
     */
    private suspend fun detectLanguageFromValuesXml(app: ExternalApp, paths: List<String>): String? {
        val baseFiles = paths
            .filter { RES_DEFAULT_VALUES_REGEX.containsMatchIn(it) && it.endsWith(".xml", ignoreCase = true) }
            .sortedBy { valueFileOrder(it) }
            .take(MAX_BASE_STRING_FILES)
        if (baseFiles.isEmpty()) return null
        val text = buildString {
            for (file in baseFiles) {
                val xml = fetchRaw(app, file) ?: continue
                ANDROID_STRING_VALUE_REGEX.findAll(xml).forEach { match ->
                    val value = unescapeAndroidString(match.groupValues[1])
                    // A resource reference ("@string/…") is not human text — skip it so it can't skew
                    // the detection.
                    if (value.isNotBlank() && !value.startsWith("@")) {
                        append(value)
                        append(' ')
                    }
                }
                if (length >= MAX_BASE_STRING_CHARS) break
            }
        }
        return LanguageDetector.detect(text)
    }

    /**
     * Language of the app's own UI text when it's written straight into Jetpack Compose / Kotlin source
     * as string literals rather than `res/values/` resources — an increasingly common pattern for
     * Compose-first apps (a real example: a French-only call blocker whose entire `res/values/strings.xml`
     * is just its app name, with every visible label a hardcoded French string literal inside its
     * `@Composable` screens). Reads only the UI-layer Kotlin files (screens, sheets, dialogs, …, see
     * [isUiSourcePath]) — not service/network/util code, which is far likelier to hold English log and
     * error strings that would skew the guess — and language-detects their multi-word string literals.
     * A single bare token ("app_name", an identifier, a tag) is a key, not a sentence, so only literals
     * with an internal space are sampled. Null when no UI source is found or the text is too thin or too
     * ambiguous to tell confidently.
     */
    private suspend fun detectLanguageFromUiSource(app: ExternalApp, paths: List<String>): String? {
        val uiFiles = paths
            .filter { it.endsWith(".kt", ignoreCase = true) && isUiSourcePath(it) }
            .sortedBy { uiSourceFileOrder(it) }
            .take(MAX_UI_SOURCE_FILES)
        if (uiFiles.isEmpty()) return null
        val text = buildString {
            for (file in uiFiles) {
                val source = fetchRaw(app, file) ?: continue
                KOTLIN_STRING_LITERAL_REGEX.findAll(source).forEach { match ->
                    val literal = match.groupValues[1]
                    // Only multi-word literals are UI sentences carrying function words to score; a lone
                    // token is a key/tag/identifier. An interpolated `${'$'}count` just adds an unmatched
                    // long token, so no template stripping is needed to keep the sample clean.
                    if (literal.any { it.isWhitespace() } && literal.any { it.isLetter() }) {
                        append(literal)
                        append(' ')
                    }
                }
                if (length >= MAX_UI_SOURCE_CHARS) break
            }
        }
        return LanguageDetector.detect(text)
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

    /** Shared cache for [fetchTreePaths], keyed by repo identity — [fetchIconCandidates],
     *  [fetchRepoMetadata] and [fetchSourceLocales] each need the same file listing, and without this
     *  they'd each fire their own request (up to 3 for the same repo in the same scan/screen-open). Also
     *  outlives any single screen/ViewModel (this class is a singleton for the app's process lifetime),
     *  so reopening an app's detail page doesn't burn a fresh api.github.com call every time either.
     *  [TREE_CACHE_TTL_MS] keeps it from going stale forever: rare (new translations, new files) but real,
     *  so a background sync/reopen after that window re-checks for real instead of trusting this forever. */
    private val treeCache = ConcurrentHashMap<String, Pair<Long, List<String>>>()

    /** The repo's whole file tree (blob paths), or empty on any failure. Each provider exposes a
     *  recursive tree API; GitHub and Gitea/Forgejo share the same `{tree:[…]}` shape, GitLab returns a
     *  paged array. See [treeCache]. */
    private suspend fun fetchTreePaths(app: ExternalApp): List<String> {
        val cacheKey = "${app.provider}|${app.effectiveHost}|${app.owner}|${app.repo}"
        treeCache[cacheKey]?.let { (fetchedAt, paths) ->
            if (SystemClock.elapsedRealtime() - fetchedAt < TREE_CACHE_TTL_MS) return paths
        }
        val paths = when (app.provider) {
            SourceProvider.GITHUB -> fetchGithubTreePaths(app)

            SourceProvider.CODEBERG -> {
                val url = "https://${app.effectiveHost}/api/v1/repos/${app.repoPath}" +
                    "/git/trees/HEAD?recursive=true&per_page=1000"
                val text = runCatching { getText(url) }.getOrNull() ?: return emptyList()
                runCatching { parseTreePaths(text, "${app.owner}/${app.repo}") }.getOrNull() ?: emptyList()
            }

            SourceProvider.GITLAB -> fetchGitlabTreePaths(app)
        }
        if (paths.isNotEmpty()) treeCache[cacheKey] = SystemClock.elapsedRealtime() to paths
        return paths
    }

    /** GitLab's tree API is a paged bare array (max 100/page); walk a bounded number of pages. Large
     *  repos (a big app with translated fastlane screenshots for every locale, e.g.) can run to several
     *  thousand entries, so this is generous rather than the small handful of pages the other
     *  tree-dependent features (icons, app name) need — a truncated listing here silently hides whole
     *  res/values-xx/ directories from [fetchSourceLocales] if they sort late. */
    private suspend fun fetchGitlabTreePaths(app: ExternalApp): List<String> {
        val encoded = app.gitlabProjectPath
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
        if (paths.size >= GITLAB_TREE_MAX_PAGES * 100) {
            Log.d(TAG, "${app.owner}/${app.repo}: GitLab tree listing hit the page cap, likely incomplete")
        }
        return paths
    }

    /**
     * GitHub's own tree listing: a single recursive request, the cheapest path and enough for the vast
     * majority of repos. GitHub caps that single request (100,000 entries / ~7MB) and marks a response
     * that hit the cap `truncated: true` with no further pagination for that *same* request — unlike
     * GitLab/Codeberg above, whose APIs page or accept a much larger per_page. A repo big enough to hit
     * that cap (confirmed real: localsend/localsend, a monorepo bundling five native clients plus
     * fastlane store-listing metadata for dozens of locales) would otherwise silently lose whole
     * directories from every feature built on this listing — icons, the app name, TV detection, and
     * especially the language cross-check, whose entire point is finding a translations folder that a
     * truncated response can drop outright. Falls back to [walkGithubTree] only when that happens, so
     * the common case still costs a single request.
     */
    private suspend fun fetchGithubTreePaths(app: ExternalApp): List<String> {
        val url = "https://api.github.com/repos/${app.repoPath}/git/trees/HEAD?recursive=1"
        val text = runCatching { getText(url, github = true) }.getOrNull() ?: return emptyList()
        val response = runCatching { json.decodeFromString(TreeResponse.serializer(), text) }
            .getOrNull() ?: return emptyList()
        if (!response.truncated) return response.tree.filter { it.type == "blob" }.map { it.path }
        Log.d(TAG, "${app.owner}/${app.repo}: tree listing truncated by GitHub, walking directories instead")
        return walkGithubTree(app)
    }

    /**
     * Breadth-first, one-directory-at-a-time fallback for a GitHub repo whose single recursive tree
     * request came back truncated (see [fetchGithubTreePaths]): GitHub's non-recursive `git/trees/{sha}`
     * lists just one directory's own direct children and isn't subject to the same single-request cap,
     * so walking it level by level still reaches everything a huge repo's flat listing couldn't fit in
     * one response. Each entry's own `path` is only its bare name at that level (not the full path),
     * rebuilt here by prefixing the parent directory walked so far. Breadth-first on purpose: it resolves
     * the shallow directories a translations folder actually lives in (assets/, res/, lib/, a couple of
     * levels deep) before a large but irrelevant subtree can exhaust the call budget — confirmed real for
     * localsend/localsend, whose fastlane store-listing metadata alone fans out into 50+ per-locale
     * directories. Capped at [GITHUB_TREE_WALK_MAX_CALLS] requests (counting failed attempts too, since
     * those still spend real quota) so one pathological repo can't burn through the whole rate-limit
     * budget; logs when the cap is hit rather than silently returning a still-incomplete listing
     * unremarked.
     */
    private suspend fun walkGithubTree(app: ExternalApp): List<String> {
        val paths = mutableListOf<String>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue.addLast("" to "HEAD")
        var calls = 0
        while (queue.isNotEmpty() && calls < GITHUB_TREE_WALK_MAX_CALLS) {
            val (parentPath, sha) = queue.removeFirst()
            calls++
            val url = "https://api.github.com/repos/${app.repoPath}/git/trees/${sha.urlPathSegment()}"
            val text = runCatching { getText(url, github = true) }.getOrNull() ?: continue
            val entries = runCatching { json.decodeFromString(TreeResponse.serializer(), text) }
                .getOrNull()?.tree ?: continue
            for (entry in entries) {
                val fullPath = if (parentPath.isEmpty()) entry.path else "$parentPath/${entry.path}"
                when (entry.type) {
                    "blob" -> paths += fullPath
                    "tree" -> queue.addLast(fullPath to entry.sha)
                    // A "commit" entry is a git submodule: neither a real file nor a directory to
                    // descend into, so it's left out of both the path list and the walk queue.
                }
            }
        }
        if (queue.isNotEmpty()) {
            Log.d(TAG, "${app.owner}/${app.repo}: GitHub tree walk hit the call cap, likely incomplete")
        }
        return paths
    }

    /**
     * Resolves the app's user-facing name from its manifest's `<application android:label>` — the same
     * value the launcher shows. A literal label is used as-is; an `@string/name` reference is resolved
     * from the module's `res/values` string files (split files included); a `${'$'}{placeholder}` from the
     * module's build file. Tries the manifests most likely to be the app module first, skipping library
     * modules that carry no application label. Returns null when it can't be determined (the UI then
     * keeps the repo name).
     */
    private suspend fun resolveAppName(app: ExternalApp, paths: List<String>): String? {
        for (manifestPath in pickManifestPaths(paths)) {
            val xml = fetchRaw(app, manifestPath) ?: continue
            val label = extractApplicationLabel(xml) ?: continue
            val moduleRoot = manifestPath.removeSuffix("/src/main/AndroidManifest.xml")
            val stringName = label.removePrefix("@string/").takeIf { it != label }
            if (stringName == null) {
                val literal = resolveManifestPlaceholders(app, moduleRoot, label) ?: continue
                return unescapeAndroidString(literal).takeIf { it.isNotBlank() }
            }
            resolveStringResource(app, paths, moduleRoot, stringName)?.let { return it }
        }
        return null
    }

    /**
     * Fills in a manifest label's `${'$'}{name}` build-time placeholders from the `manifestPlaceholders`
     * entries in the module's own build file, the way the manifest merger does when the app is built.
     * A label with no placeholder is returned untouched.
     *
     * Needed because a placeholder is the only part of a manifest that has no value until a build runs,
     * so reading the file alone gives the raw `${'$'}{appLabel}`, which is what an app whose name is chosen
     * per build type ends up called on its own page (confirmed on fluxerapp/flutter_client, shown as
     * literally "${'$'}{appLabel}"). Null when the value can't be found, so the caller falls back to the repo
     * name rather than showing the placeholder itself.
     *
     * The first value in the file wins: a build file sets the default in `defaultConfig` and then
     * overrides it per build type, in that order, so the first is the one the release build keeps while
     * the later ones are the "… Beta"/"… Canary" variants of the same name.
     */
    private suspend fun resolveManifestPlaceholders(
        app: ExternalApp,
        moduleRoot: String,
        label: String,
    ): String? {
        if (!MANIFEST_PLACEHOLDER_REGEX.containsMatchIn(label)) return label
        val prefix = if (moduleRoot.isEmpty()) "" else "$moduleRoot/"
        val buildFile = BUILD_FILE_NAMES.firstNotNullOfOrNull { fetchRaw(app, prefix + it) } ?: return null
        return substituteManifestPlaceholders(label) { manifestPlaceholderValue(buildFile, it) }
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

    /** [repoLabel] is only for the truncation log line below — GitHub/Gitea mark a tree response
     *  `truncated: true` when the repo is too large for one request (their recursive tree API isn't
     *  paged like GitLab's), which would otherwise silently drop whole directories with no signal. */
    private fun parseTreePaths(text: String, repoLabel: String): List<String> {
        val response = json.decodeFromString(TreeResponse.serializer(), text)
        if (response.truncated) {
            Log.d(TAG, "$repoLabel: tree listing truncated by the provider, likely incomplete")
        }
        return response.tree.filter { it.type == "blob" }.map { it.path }
    }

    /**
     * The project README as HTML, for display on the detail screen. Fetched as raw Markdown from the
     * provider's branchless raw base and rendered locally ([renderMarkdownToHtml]) for every provider,
     * including GitHub: GitHub's own rendered-HTML endpoint lives under api.github.com and would count
     * against the same 60-requests/hour anonymous budget as every other call, for a README that changes
     * far less often than it gets viewed. raw.githubusercontent.com isn't subject to that limit (the
     * same host is already relied on for icons, manifests and build files), so rendering locally here
     * too avoids spending quota on it at all — at the cost of GitHub's extra rendering polish (issue/PR
     * autolinking, emoji shortcodes), which Codeberg/GitLab README users already live with. Relative
     * images are fetched here and inlined as data URIs, since a raw host serves either the file or an
     * HTML viewer page depending on request headers the WebView doesn't send for sub-resources. Returns
     * null on any failure or when there is no README.
     */
    suspend fun readmeHtml(app: ExternalApp): String? = withContext(Dispatchers.IO) {
        runCatching {
            val markdown = fetchRawReadme(app) ?: return@runCatching null
            inlineRelativeImages(renderMarkdownToHtml(markdown), app)
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
     * The project's issue tracker URL, when the repo actually has issues enabled — mirrors the
     * F-Droid catalogue's "Issue tracker" link, which an external source has no equivalent metadata
     * for, so this asks the provider directly. Null when issues are disabled or the check itself
     * fails, so the caller can say there's no tracker instead of linking to a disabled page.
     */
    suspend fun fetchIssueTrackerUrl(app: ExternalApp): String? = withContext(Dispatchers.IO) {
        runCatching {
            val enabled = when (app.provider) {
                // GitHub and Gitea/Forgejo (codeberg.org or self-hosted) share the has_issues field.
                SourceProvider.GITHUB, SourceProvider.CODEBERG -> fetchRepoInfo(app)?.hasIssues ?: false
                SourceProvider.GITLAB -> {
                    // GitLab's project API doesn't reliably expose an issues-enabled flag to anonymous
                    // requests; the issues list endpoint itself returns 403 when issues are disabled
                    // for the project (200, even with an empty list, otherwise), so that doubles as
                    // the check without needing a second request.
                    val path = app.gitlabProjectPath
                    getText("https://${app.effectiveHost}/api/v4/projects/$path/issues?per_page=1") != null
                }
            }
            if (!enabled) return@runCatching null
            val suffix = if (app.provider == SourceProvider.GITLAB) "-/issues" else "issues"
            "https://${app.effectiveHost}/${app.repoPath}/$suffix"
        }.getOrNull()
    }

    /**
     * The project's own declared website, when the repo carries one: GitHub's `homepage` field, Gitea/
     * Codeberg's `website` field, both shown in the provider's own sidebar as a dedicated "Website" box
     * distinct from the repo's own page. Mirrors the F-Droid catalogue's "Project website" link, which an
     * external source has no equivalent metadata for. GitLab's project API exposes nothing equivalent, so
     * this is always null there. Null when the field is empty/absent or the check itself fails.
     */
    suspend fun fetchWebsiteUrl(app: ExternalApp): String? = withContext(Dispatchers.IO) {
        fetchRepoInfo(app)?.websiteUrl
    }

    /** Shared cache for [fetchRepoInfo], keyed and bounded exactly like [treeCache]: asking about a
     *  repo's issue tracker and its website both read this same small JSON response, and without this
     *  they'd each fire their own request against the same URL when the detail screen opens both. */
    private val repoInfoCache = ConcurrentHashMap<String, Pair<Long, RepoInfoDto?>>()

    /** GitHub's and Gitea/Codeberg's repo REST payload in one small DTO, covering everything read off it:
     *  whether issues are enabled, and the project's declared website. Null (not cached, see [treeCache]'s
     *  own reasoning) when the request or the parse fails, so a transient failure retries on the next call
     *  instead of caching "nothing here" for [TREE_CACHE_TTL_MS]. Always null for GitLab, which has no
     *  matching single-request repo-info endpoint. */
    private suspend fun fetchRepoInfo(app: ExternalApp): RepoInfoDto? {
        val cacheKey = "${app.provider}|${app.effectiveHost}|${app.owner}|${app.repo}"
        repoInfoCache[cacheKey]?.let { (fetchedAt, info) ->
            if (SystemClock.elapsedRealtime() - fetchedAt < TREE_CACHE_TTL_MS) return info
        }
        val url = when (app.provider) {
            SourceProvider.GITHUB -> "https://api.github.com/repos/${app.repoPath}"
            SourceProvider.CODEBERG -> "https://${app.effectiveHost}/api/v1/repos/${app.repoPath}"
            SourceProvider.GITLAB -> return null
        }
        val info = getText(url, github = app.provider == SourceProvider.GITHUB)
            ?.let { text -> runCatching { json.decodeFromString(RepoInfoDto.serializer(), text) }.getOrNull() }
        if (info != null) repoInfoCache[cacheKey] = SystemClock.elapsedRealtime() to info
        return info
    }

    /**
     * URL to the project's changelog, when it ships one at the repo root under a common name — mirrors
     * the F-Droid catalogue's "Changelog" link, which comes from the index's own metadata; an external
     * source has none, so this looks for the file itself. Some projects (RustDesk among them) keep no
     * such file at all and document every change exclusively through the provider's own Releases
     * feature instead, so that's tried next, linking to the releases page itself. Null when neither a
     * file nor any release with actual notes exists.
     */
    suspend fun fetchChangelogUrl(app: ExternalApp): String? = withContext(Dispatchers.IO) {
        for (name in CHANGELOG_NAMES) {
            if (runCatching { getText(app.readmeBaseUrl + name) }.getOrNull() != null) {
                return@withContext app.fileViewUrl(name)
            }
        }
        val hasReleaseNotes = runCatching { genuineReleaseNotes(app).isNotEmpty() }.getOrDefault(false)
        if (hasReleaseNotes) app.releasesUrl else null
    }

    /**
     * The repo's most recent releases that carry genuine per-version notes — filters out both empty
     * bodies and the case where every fetched release repeats the exact same static text. A maintainer's
     * release automation sometimes pastes one boilerplate file into every release instead of writing
     * real per-version notes (confirmed on AnkiDroid, whose releases all carry the identical generic
     * "which APK to install" blurb) — that isn't a changelog, and confidently rendering duplicate
     * boilerplate as one would be worse than reporting none. A single release's notes are always trusted
     * as-is (nothing to compare against). Empty when there's nothing genuine, or on any failure.
     */
    private suspend fun genuineReleaseNotes(app: ExternalApp): List<Release> {
        val notes = fetchReleases(app.provider, app.effectiveHost, app.owner, app.repo)
            .filterNot { it.body.isNullOrBlank() }
            .take(CHANGELOG_RELEASE_NOTES_LIMIT)
        val looksGenuine = notes.size <= 1 || notes.map { it.body }.distinct().size > 1
        return if (looksGenuine) notes else emptyList()
    }

    /**
     * The project's changelog, rendered as HTML exactly like [readmeHtml] — shown in its own in-app
     * page instead of opening the browser, so checking what's new doesn't leave the app. Falls back to
     * the provider's own release notes (see [genuineReleaseNotes]) when no changelog file exists — see
     * [fetchChangelogUrl]. Null when there's genuinely neither (or on any failure).
     */
    suspend fun fetchChangelogHtml(app: ExternalApp): String? = withContext(Dispatchers.IO) {
        runCatching {
            for (name in CHANGELOG_NAMES) {
                val markdown = getText(app.readmeBaseUrl + name) ?: continue
                return@runCatching inlineRelativeImages(renderMarkdownToHtml(markdown), app)
            }
            val releaseNotes = genuineReleaseNotes(app)
            if (releaseNotes.isEmpty()) return@runCatching null
            val markdown = releaseNotes.joinToString("\n\n---\n\n") { "## ${it.tag}\n\n${it.body}" }
            inlineRelativeImages(renderMarkdownToHtml(markdown), app)
        }.getOrNull()
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
        // Bounded while reading, not after: the size check used to run on an already-fully-buffered
        // body, so a host that simply omits Content-Length could send as much as it liked and the
        // check came too late to matter.
        val bytes = response.bodyBytesAtMost(MAX_INLINE_IMAGE_BYTES) ?: return null
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
        versionExcludeFilter: String? = null,
    ): Release? = withContext(Dispatchers.IO) {
        val releases = runCatching {
            fetchReleases(
                provider, host, owner, repo,
                minQualifying = 1,
                isQualifying = { it.isAllowedBy(includePrereleases, versionExcludeFilter) },
            )
        }.getOrNull()
        val picked = releases?.pickInstallable(includePrereleases, apkFilter, versionExcludeFilter)
        if (picked == null) {
            // The caller (downloadAndInstall) can't tell "the request itself failed" apart from
            // "it succeeded but nothing in the recent window was installable" — both surface as the
            // same generic "couldn't reach" message. Logged here so the real cause (fetch failure vs. no
            // installable candidate, e.g. every recent release is a pre-release with includePrereleases
            // off, or none ship a matching APK) is visible in Logcat instead of only that one message.
            Log.w(
                TAG,
                "$owner/$repo: no installable release (fetched=${releases?.size ?: "fetch failed"}, " +
                    "includePrereleases=$includePrereleases, apkFilter=$apkFilter, " +
                    "versionExcludeFilter=$versionExcludeFilter)",
            )
        }
        picked
    }

    /**
     * Fetches and decodes releases page by page (newest first), stopping once at least
     * [minQualifying] satisfy [isQualifying], the repo runs out of releases, or
     * [RELEASE_FETCH_MAX_PAGES] is reached — so a source whose most recent releases are mostly
     * excluded (several unstable channels sharing one repo, e.g. a browser's Nightly/Beta/Stable)
     * still surfaces real candidates instead of giving up after the first page. Returns everything
     * fetched, not just the qualifying ones, so callers keep their own filtering/ordering logic.
     * Shared by [latestRelease]/[latestReleaseLookup] (pick one to offer) and [releaseHistory] (lists
     * them for the user to choose from) with a matching [isQualifying], and by [genuineReleaseNotes]
     * (default arguments: any single non-empty page is "enough"). Empty (not null/throwing) when the
     * very first request fails.
     */
    private suspend fun fetchReleases(
        provider: SourceProvider,
        host: String,
        owner: String,
        repo: String,
        minQualifying: Int = 1,
        isQualifying: (Release) -> Boolean = { true },
    ): List<Release> {
        val all = mutableListOf<Release>()
        for (page in 1..RELEASE_FETCH_MAX_PAGES) {
            val batch = fetchReleasesPage(provider, host, owner, repo, page)
            if (batch.isEmpty()) break
            all += batch
            if (all.count(isQualifying) >= minQualifying) return all
            if (batch.size < RELEASES_PER_PAGE) break
        }
        if (all.count(isQualifying) < minQualifying) {
            Log.d(
                TAG,
                "$owner/$repo: only ${all.count(isQualifying)}/$minQualifying qualifying releases " +
                    "within $RELEASE_FETCH_MAX_PAGES page(s) (${all.size} fetched)",
            )
        }
        return all
    }

    /** One page of a repo's most recent releases, provider-appropriate URL and payload shape. Empty
     *  (not null/throwing) when the request itself fails. */
    private suspend fun fetchReleasesPage(
        provider: SourceProvider,
        host: String,
        owner: String,
        repo: String,
        page: Int,
    ): List<Release> {
        val text = when (provider) {
            SourceProvider.GITHUB -> getText(
                url = "https://api.github.com/repos/${repoPath(owner, repo)}/releases" +
                    "?per_page=$RELEASES_PER_PAGE&page=$page",
                github = true,
            )
            // Gitea/Forgejo: codeberg.org or any self-hosted instance (same REST shape).
            SourceProvider.CODEBERG -> getText(
                url = "https://$host/api/v1/repos/${repoPath(owner, repo)}/releases" +
                    "?limit=$RELEASES_PER_PAGE&page=$page",
            )
            SourceProvider.GITLAB -> {
                val path = gitlabProjectPath(owner, repo)
                getText(
                    url = "https://$host/api/v4/projects/$path/releases" +
                        "?per_page=$RELEASES_PER_PAGE&page=$page",
                )
            }
        } ?: return emptyList()
        return when (provider) {
            SourceProvider.GITHUB, SourceProvider.CODEBERG ->
                decodeRest(text).filterNot { it.draft }.map { it.toRelease() }
            SourceProvider.GITLAB -> decodeGitlab(text).map { it.toRelease() }
        }
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
                        url = "https://api.github.com/users/${owner.urlPathSegment()}/repos" +
                            "?per_page=100&page=$page&type=owner&sort=pushed",
                        github = true,
                    ) ?: return@pagedAccountRepos PageResult(emptyList(), 0)
                    giteaPage(text, owner, includeForks)
                }

                SourceProvider.CODEBERG -> pagedAccountRepos { page ->
                    val text = getText(
                        url = "https://$host/api/v1/users/${owner.urlPathSegment()}/repos?limit=50&page=$page",
                    ) ?: return@pagedAccountRepos PageResult(emptyList(), 0)
                    giteaPage(text, owner, includeForks)
                }

                SourceProvider.GITLAB -> {
                    // A GitLab account name can be a user or a group; try user projects first, then group
                    // projects (including subgroups) when that yields nothing.
                    val user = pagedAccountRepos { page ->
                        gitlabProjects(
                            "https://$host/api/v4/users/${owner.urlPathSegment()}" +
                                "/projects?per_page=100&page=$page",
                        )
                    }
                    user.ifEmpty {
                        pagedAccountRepos { page ->
                            gitlabProjects(
                                "https://$host/api/v4/groups/${owner.urlPathSegment()}/projects" +
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
        versionExcludeFilter: String?,
    ): Release? {
        val candidates = allowedBy(includePrereleases, versionExcludeFilter)
        return candidates.firstOrNull { it.hasCompatibleApk(filter = apkFilter) }
            ?: candidates.firstOrNull { release ->
                release.assets.any { it.name.endsWith(".apk", ignoreCase = true) }
            }
    }

    private suspend fun getText(url: String, github: Boolean = false): String? {
        val response = try {
            httpClient.get(url) {
                if (github) {
                    header("Accept", "application/vnd.github+json")
                    header("X-GitHub-Api-Version", "2022-11-28")
                    githubAuthToken()?.let { header("Authorization", "Bearer $it") }
                }
            }
        } catch (e: Exception) {
            // Every caller wraps this in runCatching and silently falls back to null/empty on failure
            // (a genuine network error must never crash a refresh or block the UI) — but that used to
            // make a real failure indistinguishable from "nothing to report" in Logcat too. Logged here,
            // the one shared place every request passes through, instead of at each of the many call
            // sites.
            Log.w(TAG, "GET $url failed", e)
            throw e
        }
        if (github) {
            // GitHub signals the rate limit with 403/429 and X-RateLimit-Remaining: 0. The header is
            // sent on every response, success or failure, so it doubles as a live quota gauge.
            val remaining = response.headers["X-RateLimit-Remaining"]?.toIntOrNull()
            remaining?.let { _rateLimitRemaining.value = it }
            val status = response.status.value
            rateLimited = (status == 403 || status == 429) && remaining == 0
            when {
                // githubAuthToken() != null is defensive, not load-bearing: GitHub only ever answers 401
                // when credentials were actually sent and rejected, never for a plain anonymous request.
                status == 401 && githubAuthToken() != null -> {
                    unauthorizedStreak++
                    if (unauthorizedStreak >= TOKEN_INVALID_STREAK) _githubTokenInvalid.value = true
                }
                response.status.isSuccess() -> {
                    unauthorizedStreak = 0
                    _githubTokenInvalid.value = false
                }
                // Any other outcome (404, 500, the 403/429 rate limit above, …) says nothing about
                // whether the token itself is valid, so it neither builds the streak nor clears it.
            }
        }
        if (!response.status.isSuccess()) {
            Log.w(TAG, "GET $url -> HTTP ${response.status.value}")
            return null
        }
        // Every response read here comes from a host the user pointed this app at, which for a
        // self-hosted Gitea/Forgejo instance is any host at all. Reading one whole into memory with
        // no ceiling means such a host can end the app by answering a release listing with gigabytes.
        // Null reads as "this call failed", which every caller already handles.
        val body = response.bodyTextAtMost(MAX_RESPONSE_BYTES)
        if (body == null) Log.w(TAG, "GET $url -> response larger than $MAX_RESPONSE_BYTES bytes, dropped")
        return body
    }

    @Serializable
    private data class TreeResponse(
        val tree: List<TreeEntry> = emptyList(),
        val truncated: Boolean = false,
    )

    @Serializable
    private data class TreeEntry(val path: String = "", val type: String = "", val sha: String = "")

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
        const val TAG = "ExternalApi"

        /** Releases fetched per page/request — a generous size costs no extra rate-limit quota (still
         *  one request), only a bigger response body. When one page isn't enough, [fetchReleases]
         *  fetches further pages up to [RELEASE_FETCH_MAX_PAGES]. */
        const val RELEASES_PER_PAGE = 30

        /** How many pages [fetchReleases] will walk while looking for enough qualifying releases —
         *  bounds the worst-case request cost for a source whose recent history is mostly excluded
         *  (several unstable channels sharing one repo, e.g. a browser's Nightly/Beta/Stable). */
        const val RELEASE_FETCH_MAX_PAGES = 5

        /** Page cap when listing an account's repos, so a huge account can't spin forever. */
        const val ACCOUNT_REPOS_MAX_PAGES = 5

        /** Consecutive HTTP 401s required before [githubTokenInvalid] latches true — see
         *  [unauthorizedStreak]'s own doc comment. */
        const val TOKEN_INVALID_STREAK = 2

        /** GitHub's own quota-status endpoint — reading it never itself counts against the quota it
         *  reports, unlike essentially every other REST call, which makes it the cheapest real way to
         *  validate a token (see [verifyGithubToken]). */
        const val GITHUB_RATE_LIMIT_URL = "https://api.github.com/rate_limit"

        /** The two spellings of a Gradle module's build file, Kotlin DSL first (today's default). */
        val BUILD_FILE_NAMES = listOf("build.gradle.kts", "build.gradle")

        /** Where an Android app's `applicationId` usually lives, most likely module first. */
        val BUILD_GRADLE_PATHS = listOf("app", "android/app", "src/app")
            .flatMap { module -> BUILD_FILE_NAMES.map { "$module/$it" } }

        /** `applicationId`, else `namespace`, in either Groovy or Kotlin-DSL form. */
        val PACKAGE_ID_REGEXES = listOf(
            Regex("""applicationId\s*[=(]?\s*["']([\w.]+)["']"""),
            Regex("""namespace\s*[=(]?\s*["']([\w.]+)["']"""),
        )
    }
}

/**
 * The ceiling [ExternalApi.releaseHistory] fetches and returns when the caller wants the full version
 * list, rather than only the handful shown before a "show more" tap (see
 * [com.looker.droidify.compose.externalApps.ExternalAppDetailScreen]'s own smaller default). Internal
 * rather than private: both the phone and the Android TV detail screens ask for this exact ceiling once
 * the user wants everything, so it lives here once instead of the same number typed twice.
 */
internal const val RELEASE_HISTORY_TARGET = 10

/** How many distinct icon candidates we keep for the picker (one per icon family, best density). */
private const val MAX_ICON_CANDIDATES = 12

/** Freshness window for [ExternalApi.treeCache] — long enough that browsing an app's screens or a
 *  catalogue re-scan doesn't burn a fresh api.github.com call per repo, short enough that a genuinely
 *  new translation or source file shows up again within the same day rather than staying cached forever.
 *  Matches [com.looker.droidify.compose.externalApps.ExternalAppsViewModel]'s own per-app caches. */
private const val TREE_CACHE_TTL_MS = 15 * 60 * 1000L

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
    // "source" excludes the master asset some projects keep next to the real icon for regenerating it
    // (confirmed on Victor-root/OpenMessages: mipmap-xxxhdpi/icon_source.png, a 1024px near-white glyph
    // with no background, sitting beside the real ic_launcher.png): "icon"-prefixed and otherwise
    // unmarked, it tied the real icon on both rank and density and depended on tree-listing order to
    // lose, which is exactly the kind of accidental win this list exists to close off deliberately
    // instead of by luck.
    val excluded = listOf(
        "background", "monochrome", "notification", "splash", "banner", "feature", "badge", "store",
        "source",
    )
    if (excluded.any { stem.contains(it) }) return null
    val launcherish = stem.startsWith("ic_launcher") || stem.startsWith("icon") ||
        stem.startsWith("appicon") || stem.startsWith("app_icon") || stem.contains("launcher")
    if (!launcherish) return null
    // A "_foreground"/"_round" suffix marks an adaptive component; any other launcher-ish base name
    // (ic_launcher, launcher_icon, icon…) is the fully-composed square icon and is preferred — so a
    // transparent foreground never outranks the real icon, whatever the project names it.
    return when {
        // Adaptive-icon background layer — a solid colour/pattern alone, never a usable standalone icon.
        // "_back"/"_bg" are common abbreviations of "_background" the excluded list above only catches
        // spelled out in full.
        stem.endsWith("_back") || stem.endsWith("_bg") -> null
        // "_fore"/"_fg" are the same kind of abbreviation for "_foreground" — confirmed on
        // fgl27/smarttwitchtv, whose adaptive layers are ic_launcher_adaptive_fore/_back rather than the
        // full words, which let the (near-transparent alone) foreground layer outrank the real composed
        // ic_launcher.png on density and get picked as the app's icon — rendering solid white.
        stem.endsWith("_foreground") || stem.endsWith("_fore") || stem.endsWith("_fg") -> 2
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

/** Matches a `res/values-<qualifier>/<file>` path, capturing the qualifier — the same convention
 *  Android resource directories always use, regardless of module depth. Also matches
 *  `composeResources/values-<qualifier>/<file>`: JetBrains Compose Multiplatform's own resource
 *  system (`org.jetbrains.compose.resources`) reuses Android's exact `values-<qualifier>` naming, just
 *  under its own generated `composeResources` directory instead of a real `res/` — confirmed on
 *  NewPipe's in-progress Compose Multiplatform rewrite (`shared/src/commonMain/composeResources/
 *  values-iw/strings.xml`), which the plain `res/` form alone completely missed. */
private val RES_VALUES_DIR_REGEX = Regex("""/(?:res|composeResources)/values-([^/]+)/[^/]+$""")

/** Matches a file directly inside an unqualified `res/values/` (no "-xx" suffix at all) — the app's
 *  base/default strings, treated as English (see [ExternalApi.fetchSourceLocales]). Also matches the
 *  `composeResources/values/` equivalent — see [RES_VALUES_DIR_REGEX]. */
private val RES_DEFAULT_VALUES_REGEX = Regex("""/(?:res|composeResources)/values/[^/]+$""")

/** A plain locale qualifier: a 2-3 letter ISO 639 code, optionally with a `-r<REGION>` region (a
 *  2-letter ISO 3166 code or a 3-digit UN M.49 area code) — e.g. "fr", "pt-rBR", "es-r419". */
private val SIMPLE_LOCALE_QUALIFIER_REGEX = Regex("""^([a-z]{2,3})(-r([A-Z]{2}|[0-9]{3}))?$""")

/** The BCP47 form Android also accepts for locales with no 2-letter ISO 639 form or that need a
 *  script (e.g. "values-b+sr+Latn", "values-b+es+419"). The optional script tag is intentionally
 *  dropped — this only needs to match the same "language" / "language-rREGION" convention the rest of
 *  the supported-languages feature already uses. */
private val BCP47_LOCALE_QUALIFIER_REGEX =
    Regex("""^b\+([a-zA-Z]{2,3})(?:\+[A-Za-z]{4})?(?:\+([A-Za-z]{2}|[0-9]{3}))?$""")

/** Extracts a locale code in BCP47 form ("fr", "pt-BR") — what `Locale.forLanguageTag()` (used by the
 *  display code downstream) actually understands, NOT the "-r" infix Android uses only for resource
 *  *directory names* ("values-pt-rBR") — from a raw qualifier string (a `values-<qualifier>` directory
 *  name, or a [MOKO_RESOURCES_DIR_REGEX] one), or null when it matches neither the simple nor the
 *  BCP47 shape (e.g. a non-locale qualifier like "night"/"v21"/"land"). */
private fun localeCodeFromQualifier(qualifier: String): String? {
    SIMPLE_LOCALE_QUALIFIER_REGEX.matchEntire(qualifier)?.let { m ->
        val region = m.groupValues[3]
        return if (region.isNotEmpty()) "${m.groupValues[1]}-$region" else m.groupValues[1]
    }
    BCP47_LOCALE_QUALIFIER_REGEX.matchEntire(qualifier)?.let { m ->
        val region = m.groupValues[2]
        return if (region.isNotEmpty()) "${m.groupValues[1]}-$region" else m.groupValues[1]
    }
    return null
}

/** Locale from a `res/values-*`/`composeResources/values-*` path, or null when it isn't inside a
 *  locale-qualified directory at all (the default `values/`, or a non-locale qualifier). */
private fun localeFromResValuesPath(path: String): String? {
    val qualifier = RES_VALUES_DIR_REGEX.find(path)?.groupValues?.get(1) ?: return null
    return localeCodeFromQualifier(qualifier)
}

/** Matches a `moko-resources/<qualifier>/<file>` path, capturing the qualifier. moko-resources (a
 *  Kotlin Multiplatform resource library — confirmed used by Mihon) puts each locale's strings in its
 *  own bare-named directory (`moko-resources/fr/strings.xml`) instead of naming the locale into the
 *  file itself, which the file itself (always generically "strings.xml"/"plurals.xml") never carries —
 *  invisible to both [RES_VALUES_DIR_REGEX] (no `res`/`composeResources` segment at all) and the i18n
 *  file-name heuristic (the locale isn't in the file name). */
private val MOKO_RESOURCES_DIR_REGEX = Regex("""(?:^|/)moko-resources/([^/]+)/[^/]+$""")

/** Locale from a `moko-resources/<qualifier>/<file>` path — "base" is moko-resources' own name for the
 *  English source directory (mirroring `res/values`' unqualified-default convention), everything else
 *  is parsed the same way a `res/values-<qualifier>` directory name would be. Null when the qualifier
 *  matches neither. */
private fun localeFromMokoResourcesPath(path: String): String? {
    val qualifier = MOKO_RESOURCES_DIR_REGEX.find(path)?.groupValues?.get(1) ?: return null
    if (qualifier == "base") return "en"
    return localeCodeFromQualifier(qualifier)
}

/** A directory name suggesting a translation-file folder, for cross-platform (Flutter, React Native,
 *  web) apps that don't use Android's res/values-xx/ convention at all — their UI strings live in their
 *  own asset files instead, one per locale, usually inside a folder along these lines. */
private val I18N_DIR_HINT_REGEX = Regex(
    """(?:^|/)(?:i18n|l10n|intl|locales?|translations?|lang(?:uages?)?)(?:/|$)""",
    RegexOption.IGNORE_CASE,
)

/** A translation file's locale, either as the whole file name ("en.json", "pt_BR.arb") or as a
 *  trailing `_locale`/`-locale` suffix before the extension ("app_en.arb", "strings-pt-BR.json") —
 *  with an optional script subtag recognized in between and dropped ("zh-Hant-TW.json" resolves to
 *  "zh-TW", the same script-dropping [localeCodeFromQualifier] already does for BCP47 qualifiers), so
 *  a 3-subtag code is consumed whole instead of falling through to a garbled match on just its last
 *  segment. An optional non-locale infix before the extension is allowed and dropped, so a double
 *  extension like the `slang` package's own `de.i18n.json` / `zh-CN.i18n.yaml` naming is captured
 *  ("de", "zh-CN") — without it the `i18n` segment sits where the locale-or-extension is expected and
 *  the whole name failed to match, so slang (which the doc claimed to cover but the regex didn't) was
 *  silently missed. Covers Flutter (ARB, `slang`/`easy_localization`) and most JS i18n libraries
 *  (json/yaml), plus hand-rolled per-locale dictionaries some cross-platform/native projects write
 *  directly in their own language instead of a data format — Rust (e.g. RustDesk's `src/lang/fr.rs`),
 *  Dart, Java `.properties`, and gettext `.po`. Each is still gated on [I18N_DIR_HINT_REGEX] and the
 *  strict locale-code file name below, so a random source file can't be mistaken for a translation one
 *  just by sharing an extension. */
private val I18N_FILE_LOCALE_REGEX = Regex(
    """(?:^|[_-])([a-zA-Z]{2,3})(?:[_-][A-Za-z]{4})?(?:[_-]([A-Za-z]{2}|[0-9]{3}))?""" +
        """(?:\.[a-zA-Z0-9]+)?\.(?:arb|json|ya?ml|rs|dart|properties|po)$""",
)

/** Best-effort locale extraction for non-Android translation conventions: a file inside an i18n/l10n-
 *  ish folder, whose own name carries the locale. Deliberately gated on the directory hint (not run
 *  against every .json/.yaml in the repo) to avoid matching unrelated config files. Returns a BCP47 code
 *  ("fr", "pt-BR") or null when [path] doesn't look like one of these translation files. */
internal fun localeFromI18nAssetPath(path: String): String? {
    if (!I18N_DIR_HINT_REGEX.containsMatchIn(path.substringBeforeLast('/'))) return null
    val fileName = path.substringAfterLast('/')
    // Rust's own reserved module-file names ("mod.rs" declares the directory as a module, "lib.rs"/
    // "main.rs" a crate root) — near-universal in any Rust source directory, translations or not, and
    // "mod"/"lib" are short enough to otherwise look exactly like a real (if obscure) locale code.
    if (fileName.endsWith(".rs") && fileName.substringBeforeLast('.') in RUST_RESERVED_FILE_NAMES) {
        return null
    }
    val match = I18N_FILE_LOCALE_REGEX.find(fileName) ?: return null
    // A three-letter word in front of the locale ("app_fr.arb") is short enough to sit exactly where a
    // language belongs, which left the real language holding the region's place. See
    // [withoutNonLocalePrefix].
    val code = withoutNonLocalePrefix(
        match.groupValues.drop(1).filter { it.isNotEmpty() }.joinToString("-"),
    )
    val language = code.substringBefore('-')
    val region = code.substringAfter('-', "")
    return if (region.isEmpty()) language.lowercase() else "${language.lowercase()}-${region.uppercase()}"
}

private val RUST_RESERVED_FILE_NAMES = setOf("mod", "lib", "main")

/** How many 100-item pages of GitLab's tree API to walk while looking for the manifest / icons. */
private const val GITLAB_TREE_MAX_PAGES = 50

/** How many directory-level requests [ExternalApi.walkGithubTree] issues at most, walking a GitHub repo
 *  whose single recursive tree request came back truncated — same order of magnitude as
 *  [GITLAB_TREE_MAX_PAGES]'s own worst-case request count. */
private const val GITHUB_TREE_WALK_MAX_CALLS = 50

/** Largest README image inlined as a data URI; bigger ones are left as-is to avoid bloating the HTML. */
private const val MAX_INLINE_IMAGE_BYTES = 1_000_000

/**
 * Most this app will hold in memory for one API response. Far above anything a forge really answers
 * with here: the biggest of these calls is a repository's recursive file listing, which the providers
 * themselves truncate long before this, and the rest are release listings and README markdown. What
 * it is here for is the host that isn't a forge at all, since a self-hosted Gitea or Forgejo source
 * is any address the user was given.
 */
private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

/**
 * The response body, or null once it turns out to be longer than [limit], without ever buffering past
 * that. A declared Content-Length over the limit is refused before a byte is read, but the reading
 * itself is what enforces it: a host that omits the header, or states one it then exceeds, is
 * precisely the host worth guarding against.
 */
internal suspend fun HttpResponse.bodyBytesAtMost(limit: Int): ByteArray? {
    headers["Content-Length"]?.toLongOrNull()?.let { if (it > limit) return null }
    val channel = bodyAsChannel()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = channel.readAvailable(buffer)
        if (read < 0) return output.toByteArray()
        if (output.size() + read > limit) return null
        output.write(buffer, 0, read)
    }
}

/** [bodyBytesAtMost] as text, matching what `bodyAsText()` decodes to for the JSON and Markdown read
 *  here (both are UTF-8), but bounded. */
internal suspend fun HttpResponse.bodyTextAtMost(limit: Int): String? =
    bodyBytesAtMost(limit)?.toString(Charsets.UTF_8)

/** Captures the `src` of an `<img>` tag (CommonMark output and any raw HTML in the README). */
private val IMG_SRC_REGEX = Regex("""<img\b[^>]*?\bsrc="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)

/** README file names tried in order against a repo's branchless raw base. */
private val README_NAMES = listOf(
    "README.md", "readme.md", "Readme.md", "README.markdown", "README.MD", "README", "readme",
)

/** Changelog file names tried in order against a repo's branchless raw base. Mirrors [README_NAMES]'
 *  case-variant coverage, plus the other common conventions (NEWS, RELEASES) it didn't cover before. */
private val CHANGELOG_NAMES = listOf(
    "CHANGELOG.md", "changelog.md", "Changelog.md", "CHANGELOG.MD", "CHANGELOG.rst", "CHANGES.md",
    "changes.md", "CHANGES", "CHANGES.rst", "HISTORY.md", "history.md", "HISTORY.rst", "HISTORY",
    "NEWS.md", "NEWS", "RELEASES.md", "RELEASES", "CHANGELOG",
)

/** How many of a repo's most recent releases (newest first) to fold into the release-notes changelog
 *  fallback (see [ExternalApi.fetchChangelogHtml]) — enough to read like a real "what's new across the
 *  last few versions" changelog without pulling in the entire release history in one page. */
private const val CHANGELOG_RELEASE_NOTES_LIMIT = 5

/**
 * GitHub's and Gitea/Codeberg's repo REST payload, narrowed to the two fields [ExternalApi.fetchRepoInfo]
 * reads off it. GitHub calls the project's declared website `homepage`; Gitea/Codeberg calls it `website`,
 * both null on the other provider's response. `ignoreUnknownKeys` means neither name ever fails to
 * parse, and [websiteUrl] reads whichever of the two is actually present.
 */
@Serializable
private data class RepoInfoDto(
    @SerialName("has_issues") val hasIssues: Boolean = false,
    val homepage: String? = null,
    val website: String? = null,
) {
    val websiteUrl: String? get() = (homepage ?: website)?.trim()?.takeIf { it.isNotEmpty() }
}

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

/**
 * Renders Markdown to an HTML fragment locally (no network, no external service). Relative links and
 * images stay relative so the WebView resolves them against the repo's raw base. Raw HTML in the
 * source is passed through (as GitHub does), so a README keeps the layout its author wrote for it.
 * What that HTML is allowed to *do* is settled later, on the way into the WebView, by
 * [com.looker.droidify.utility.text.ReadmeHtmlSanitizer]: this used to say the raw HTML stays inert
 * because no JavaScript ever runs, which stopped being true when the "JavaScript in READMEs" setting
 * was added.
 *
 * GitHub's "alert" blockquotes (`> [!NOTE]` / `[!TIP]` / `[!IMPORTANT]` / `[!WARNING]` / `[!CAUTION]`
 * as the sole content of a blockquote's first line) are a GitHub-specific convention layered on top of
 * plain blockquote syntax, not something CommonMark itself understands — without this they'd render as
 * an ordinary quote showing the literal marker text. They're pulled out and rendered on their own before
 * the main parse, then spliced back in as the styled callout (see [extractAlertBlockquotes]);
 * [ReadmeWebView]'s CSS already ships the matching `.markdown-alert*` classes, mirroring github.com's
 * own look, for every provider — the syntax isn't GitHub-exclusive; Codeberg/Gitea and GitLab READMEs
 * use the same convention.
 */
private fun renderMarkdownToHtml(markdown: String): String {
    val (withoutAlerts, alerts) = extractAlertBlockquotes(markdown)
    var html = markdownRenderer.render(markdownParser.parse(withoutAlerts))
    alerts.forEach { (placeholder, alertHtml) ->
        html = html.replace(Regex("<p>\\s*${Regex.escape(placeholder)}\\s*</p>"), alertHtml)
    }
    return html
}

/** Matches a blockquote continuation line: up to 3 leading spaces (CommonMark's own indent allowance),
 *  a `>`, then an optional single space, capturing the rest of the line as its actual content. */
private val BLOCKQUOTE_LINE_REGEX = Regex("""^ {0,3}>[ ]?(.*)$""")

/** The five GitHub alert types, matched case-insensitively against a blockquote's first line. */
private val ALERT_MARKER_REGEX = Regex(
    """^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]$""",
    RegexOption.IGNORE_CASE,
)

/**
 * Pulls every GitHub alert blockquote out of [markdown] — recognised by its first quoted line being
 * exactly one of the five markers, GitHub's own trigger condition — replacing each with a unique
 * placeholder line so the surrounding document still parses normally. Returns the substituted markdown
 * alongside a map of placeholder to that alert's own already-rendered HTML: its body (everything in the
 * blockquote after the marker line) is parsed through the same pipeline on its own, so nested formatting
 * and links work exactly as they would inline. [renderMarkdownToHtml] splices the two back together.
 */
private fun extractAlertBlockquotes(markdown: String): Pair<String, Map<String, String>> {
    val lines = markdown.lines()
    val output = StringBuilder()
    val alerts = LinkedHashMap<String, String>()
    var i = 0
    while (i < lines.size) {
        val quoted = BLOCKQUOTE_LINE_REGEX.matchEntire(lines[i])
        val marker = quoted?.let { ALERT_MARKER_REGEX.matchEntire(it.groupValues[1].trim()) }
        if (quoted == null || marker == null) {
            output.appendLine(lines[i])
            i++
            continue
        }
        val type = marker.groupValues[1].uppercase()
        var j = i + 1
        val bodyLines = mutableListOf<String>()
        while (j < lines.size) {
            val bodyQuoted = BLOCKQUOTE_LINE_REGEX.matchEntire(lines[j]) ?: break
            bodyLines += bodyQuoted.groupValues[1]
            j++
        }
        val bodyHtml = markdownRenderer.render(markdownParser.parse(bodyLines.joinToString("\n")))
        val placeholder = "@@GH_ALERT_${alerts.size}@@"
        val title = type.lowercase().replaceFirstChar(Char::uppercase)
        alerts[placeholder] =
            """<div class="markdown-alert markdown-alert-${type.lowercase()}">""" +
                """<p class="markdown-alert-title">$title</p>$bodyHtml</div>"""
        // Blank lines around the placeholder guarantee it forms its own isolated paragraph, whatever
        // whitespace (or lack of it) originally surrounded the blockquote.
        output.appendLine()
        output.appendLine(placeholder)
        output.appendLine()
        i = j
    }
    return output.toString() to alerts
}

/** Icon candidates + the real app name detected from a source repo, shown before install, plus whether
 *  the repo's manifest declares Android TV support. */
data class RepoMetadata(
    val iconCandidates: List<String> = emptyList(),
    /** The repository's adaptive launcher icon, already composed and masked the way Android will draw
     *  it once installed. Null when the repo ships none, or below Android 8. Preferred over
     *  [iconCandidates], whose flat raster is only the pre-Android-8 fallback and is often stale. */
    val adaptiveIcon: Bitmap? = null,
    val appName: String? = null,
    val supportsTelevision: Boolean = false,
)

/** How many manifest / value files we'll fetch while resolving the app name, to bound network use. */
private const val MAX_MANIFESTS = 8
private const val MAX_VALUE_FILES = 12

/** Bounds for [ExternalApi.detectBaseLanguage]'s read of the base string files: how many `res/values/`
 *  XML files to fetch, and how much extracted text is enough to detect a language before stopping. */
private const val MAX_BASE_STRING_FILES = 8
private const val MAX_BASE_STRING_CHARS = 20_000

/** Bounds for the Compose/Kotlin UI-source fallback (see [ExternalApi.detectLanguageFromUiSource]):
 *  how many UI-layer `.kt` files to fetch, and how much extracted literal text is enough. Screens come
 *  first (see [uiSourceFileOrder]), so the cap is rarely reached before the language is already clear. */
private const val MAX_UI_SOURCE_FILES = 12
private const val MAX_UI_SOURCE_CHARS = 20_000

/** The inner text of a single-line double-quoted Kotlin string literal, for sampling a Compose app's
 *  hardcoded UI text ([ExternalApi.detectLanguageFromUiSource]). The delimiting double-quotes are the
 *  two escaped-quote string fragments on either side of the raw-string body, so the pattern reads as
 *  `"…"` (match from one double-quote to the next) while keeping the backslash-heavy body a raw string.
 *  The body allows an escaped char so an embedded escaped quote doesn't end the match early. Triple-
 *  quoted source literals aren't specifically handled; UI labels almost never use them. */
private val KOTLIN_STRING_LITERAL_REGEX = Regex("\"" + """((?:\\.|[^"\\\n])*)""" + "\"")

/** Whether a source path is UI-layer code — Compose screens/sheets/dialogs/components, or a classic
 *  Activity/Fragment — where an app's own visible text lives, as opposed to service/network/util code
 *  (more likely to carry English log and error strings). Matched on the whole path so both a `/ui/`-style
 *  package and a bare `SomethingScreen.kt` file name are covered; test sources are excluded. */
private fun isUiSourcePath(path: String): Boolean {
    val lower = path.lowercase()
    if ("/androidtest/" in lower || "/test/" in lower || "/build/" in lower) return false
    return "/ui/" in lower || "/screen" in lower || "/compose" in lower ||
        "/presentation/" in lower || "/sheet" in lower || "/dialog" in lower ||
        "/component" in lower ||
        UI_FILE_NAME_REGEX.containsMatchIn(path.substringAfterLast('/'))
}

/** A UI-layer Kotlin file by its name alone, for repos that don't package UI under a `/ui/` directory. */
private val UI_FILE_NAME_REGEX = Regex("""(Screen|Activity|Fragment|Sheet|Dialog|Composable|Page|Nav)s?\.kt$""")

/** Richest-text UI files first: full screens and sheets carry the most labels, so they alone usually
 *  suffice within [MAX_UI_SOURCE_FILES] before thinner dialog/component files are ever reached. */
private fun uiSourceFileOrder(path: String): Int {
    val lower = path.lowercase()
    return when {
        "screen" in lower -> 0
        "sheet" in lower -> 1
        "activity" in lower || "fragment" in lower -> 2
        "dialog" in lower -> 3
        else -> 4
    }
}

/** Captures the inner text of an Android `<string …>value</string>` or a string-array/plurals `<item>`
 *  element, for language-detecting an app's base UI strings ([ExternalApi.detectBaseLanguage]). */
private val ANDROID_STRING_VALUE_REGEX =
    Regex("""<(?:string|item)\b[^>]*>(.*?)</(?:string|item)>""", RegexOption.DOT_MATCHES_ALL)

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

/** A manifest's build-time `${'$'}{name}` placeholder, capturing the name the build file gives a value to.
 *
 *  The closing brace has to be escaped. Android's regex engine is ICU, not the JVM's own: the JVM reads a
 *  lone `}` as a literal, ICU rejects the whole pattern as a dangling interval. This is a top-level
 *  property, so the failure took the entire file's initialisation down with it and every external source
 *  stopped answering, on a pattern that compiles and passes its tests on a desktop JVM. */
private val MANIFEST_PLACEHOLDER_REGEX = Regex("""\${'$'}\{([^}]+)\}""")

/**
 * Replaces every `${'$'}{name}` in a manifest value with what [valueOf] gives that name, as the manifest
 * merger does at build time. A value with no placeholder comes back untouched; one placeholder without
 * a value gives up on the whole thing and returns null, since half a name is worse than none.
 */
internal fun substituteManifestPlaceholders(value: String, valueOf: (String) -> String?): String? {
    var resolved = value
    for (match in MANIFEST_PLACEHOLDER_REGEX.findAll(value)) {
        val replacement = valueOf(match.groupValues[1]) ?: return null
        resolved = resolved.replace(match.value, replacement)
    }
    return resolved
}

/**
 * The value a build file gives one `manifestPlaceholders` entry, in the spellings both Gradle DSLs
 * offer: `manifestPlaceholders["k"] = "v"` and `.put("k", "v")`, Groovy's `manifestPlaceholders.k = "v"`,
 * and the map form (`= [k: "v"]` / `+= mapOf("k" to "v")`). Earliest in the file wins whichever spelling
 * found it, so a `defaultConfig` entry beats the per-build-type overrides written after it.
 *
 * A value that isn't a plain string literal (a variable, a version-catalog lookup) matches nothing and
 * comes back null, which the caller reads as "can't tell" rather than risking a wrong name.
 */
internal fun manifestPlaceholderValue(buildFile: String, name: String): String? {
    val key = Regex.escape(name)
    // The value itself, in either quote style: the one part every spelling below ends with.
    val quoted = """["']([^"']*)["']"""
    return listOf(
        Regex("""manifestPlaceholders\s*(?:\[\s*|\.put\s*\(\s*)["']$key["']\s*(?:\]\s*=|,)\s*$quoted"""),
        Regex("""manifestPlaceholders\s*\.\s*$key\s*=\s*$quoted"""),
        Regex("""manifestPlaceholders\s*\+?=\s*(?:mapOf\s*)?[\[(][^\])]*?["']?$key["']?\s*(?:to|:)\s*$quoted"""),
    )
        .mapNotNull { it.find(buildFile) }
        .minByOrNull { it.range.first }
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

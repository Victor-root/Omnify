package com.looker.droidify.utility.apk

/**
 * Derives alternate URLs for the same file when it's served from a static-"Pages" host that doesn't
 * support HTTP range requests, so the partial-read APK inspectors ([RemoteApkManifestReader],
 * [RemoteApkLocaleReader], [ApkSigningBlockReader]) can still work without downloading the whole file.
 *
 * The insight: a `*.gitlab.io` / `*.github.io` / `*.codeberg.page` site is a published copy of a git
 * repository — the same bytes usually also exist in the repository itself, and the forge's own raw-file
 * endpoint serves them WITH range support (confirmed live against a real case: a GitLab Pages-hosted
 * F-Droid repo whose Pages host ignores Range headers entirely on every protocol, while the exact same
 * APK, committed to the backing repository, comes back 206 Partial Content from gitlab.com's raw
 * endpoint, backed by a range-capable object store).
 *
 * A candidate is only a *guess* about where the repository keeps the file (a Pages site can also be
 * built by CI from files that were never committed) — so a caller must verify it actually got the same
 * artifact before trusting anything read this way. Exact total-file-size matching (from the 206
 * response's own Content-Range header, compared against the size the repo index declares for the APK)
 * is the verification used here: two different builds of an APK matching to the exact byte is not a
 * realistic collision, and a stale mirror (same name, different version) differs in size immediately.
 * Callers must also never send the origin repo's credentials to a mirror host — these are unrelated
 * services that just happen to hold the same public file.
 */
object RangeCapableMirrors {

    private val GITLAB_PAGES = Regex("""^https?://([^./]+)\.gitlab\.io/([^/]+)/(.+)$""")
    private val GITHUB_PAGES = Regex("""^https?://([^./]+)\.github\.io/([^/]+)/(.+)$""")
    private val CODEBERG_PAGES = Regex("""^https?://([^./]+)\.codeberg\.page/([^/]+)/(.+)$""")

    /**
     * Candidate range-capable URLs for the file at [fileUrl], best guess first — empty when [fileUrl]
     * isn't on a recognized Pages host (including any custom domain, whose backing repository can't be
     * derived from the URL alone). `HEAD` resolves to the repository's default branch on every one of
     * these raw endpoints (the same convention [com.looker.droidify.external.ExternalApp.readmeBaseUrl]
     * already relies on); the GitHub/Codeberg lists also try the dedicated branch their Pages products
     * conventionally publish from, since the served files sometimes exist only there.
     */
    fun candidates(fileUrl: String): List<String> {
        GITLAB_PAGES.find(fileUrl)?.let { (owner, project, path) ->
            return listOf("https://gitlab.com/$owner/$project/-/raw/HEAD/$path")
        }
        GITHUB_PAGES.find(fileUrl)?.let { (owner, project, path) ->
            return listOf(
                "https://raw.githubusercontent.com/$owner/$project/gh-pages/$path",
                "https://raw.githubusercontent.com/$owner/$project/HEAD/$path",
            )
        }
        CODEBERG_PAGES.find(fileUrl)?.let { (owner, project, path) ->
            return listOf(
                "https://codeberg.org/$owner/$project/raw/branch/pages/$path",
                "https://codeberg.org/$owner/$project/raw/HEAD/$path",
            )
        }
        return emptyList()
    }

    /** Destructures a match's three capture groups (owner, project, remaining path). */
    private operator fun MatchResult.component1(): String = groupValues[1]
    private operator fun MatchResult.component2(): String = groupValues[2]
    private operator fun MatchResult.component3(): String = groupValues[3]
}

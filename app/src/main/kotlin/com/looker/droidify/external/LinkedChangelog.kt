package com.looker.droidify.external

import java.net.URI
import java.net.URLDecoder

/**
 * A catalogue app's changelog link that can be read in the app instead of the browser: a changelog file
 * on GitHub, GitLab or Codeberg, or one of those projects' releases page. [baseUrl] is what the page's
 * relative links and images resolve against.
 */
sealed interface LinkedChangelog {
    val baseUrl: String

    /** The project's releases page: its release notes are the changelog. */
    data class Releases(val provider: SourceProvider, val owner: String, val repo: String) : LinkedChangelog {
        val host: String get() = publicHost(provider)
        override val baseUrl: String get() = "https://$host/${repoPath(owner, repo)}/"
    }

    /** A changelog file, fetched as raw text from [rawUrl]. */
    data class File(val rawUrl: String, override val baseUrl: String) : LinkedChangelog
}

/** The in-app form of the changelog at [url], or null when it has none (a website, a wiki, a folder of
 *  per-version files, an HTML page...) and the browser stays the way to read it. */
fun linkedChangelog(url: String): LinkedChangelog? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
    val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
    // Kept encoded, so a file path is reused in the raw address exactly as the link spells it.
    val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
    val linked = when (host) {
        "github.com" -> gitHubChangelog(segments)
        "raw.githubusercontent.com", "raw.github.com" -> rawFile(
            "https://raw.githubusercontent.com/",
            segments.takeIf { it.size >= 4 },
        )
        "gitlab.com" -> gitLabChangelog(segments)
        "codeberg.org" -> codebergChangelog(segments)
        else -> null
    }
    return linked?.takeIf { it !is LinkedChangelog.File || it.rawUrl.isReadableFile() }
}

private fun gitHubChangelog(segments: List<String>): LinkedChangelog? {
    if (segments.size < 3) return null
    val (owner, repo) = segments.take(2).map(::decode)
    return when (segments[2]) {
        "releases" -> LinkedChangelog.Releases(SourceProvider.GITHUB, owner, repo.removeSuffix(".git"))
        // blob/<ref>/<path>
        "blob" -> rawFile(
            "https://raw.githubusercontent.com/",
            (segments.take(2) + segments.drop(3)).takeIf { segments.size >= 5 },
        )
        else -> null
    }
}

/** GitLab nests groups, so the project is everything before its "/-/" marker. */
private fun gitLabChangelog(segments: List<String>): LinkedChangelog? {
    val marker = segments.indexOf("-")
    if (marker < 2 || marker + 1 >= segments.size) return null
    val project = segments.take(marker)
    val rest = segments.drop(marker + 1)
    return when (rest[0]) {
        "releases" -> LinkedChangelog.Releases(
            SourceProvider.GITLAB,
            project.dropLast(1).joinToString("/") { decode(it) },
            decode(project.last()),
        )
        // blob|raw/<ref>/<path>
        "blob", "raw" -> rawFile(
            "https://gitlab.com/",
            (project + "-" + "raw" + rest.drop(1)).takeIf { rest.size >= 3 },
        )
        else -> null
    }
}

/** Codeberg's file pages read src/<branch|tag|commit>/<ref>/<path>; the file itself comes from its API's
 *  raw endpoint, which serves the real file to a non-browser client. */
private fun codebergChangelog(segments: List<String>): LinkedChangelog? {
    if (segments.size < 3) return null
    val (owner, repo) = segments.take(2)
    return when (segments[2]) {
        "releases" -> LinkedChangelog.Releases(SourceProvider.CODEBERG, decode(owner), decode(repo))
        "src", "raw" -> {
            if (segments.size < 6 || segments[3] !in setOf("branch", "tag", "commit")) return null
            val kind = segments[3]
            val ref = segments[4]
            val path = segments.drop(5)
            LinkedChangelog.File(
                rawUrl = "https://codeberg.org/api/v1/repos/$owner/$repo/raw/${path.joinToString("/")}?ref=$ref",
                baseUrl = "https://codeberg.org/$owner/$repo/raw/$kind/$ref/" +
                    path.dropLast(1).joinToString("") { "$it/" },
            )
        }
        else -> null
    }
}

/** A file at [root] + [segments], its folder as the base. Null when [segments] is. */
private fun rawFile(root: String, segments: List<String>?): LinkedChangelog.File? {
    segments ?: return null
    return LinkedChangelog.File(
        rawUrl = root + segments.joinToString("/"),
        baseUrl = root + segments.dropLast(1).joinToString("") { "$it/" },
    )
}

/** A web page is better read in the browser than as source text. */
private fun String.isReadableFile(): Boolean {
    val name = substringBefore('?').substringAfterLast('/').lowercase()
    return !name.endsWith(".html") && !name.endsWith(".htm")
}

private fun decode(segment: String): String = URLDecoder.decode(segment, "UTF-8")

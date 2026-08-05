package com.looker.droidify.external

/**
 * [this] as a single URL path segment, with everything that carries meaning inside a URL escaped.
 *
 * An account or repository name reaches this app from whatever the user was handed (a typed address,
 * a shared link) and then gets pasted straight into an API address. Left as-is, a name is not
 * necessarily a name: a `/` splits it into two segments, a `?` turns the rest of the address into a
 * query, and `..` walks a level up, so a source can be crafted whose "owner" bends the address onto
 * an endpoint the app never meant to call, while still sending the user's token along with it.
 * Escaped, a name is only ever a name.
 *
 * Ordinary names come out byte for byte identical, so every address the app builds today is
 * unchanged.
 */
internal fun String.urlPathSegment(): String {
    // "." and ".." are made only of characters a segment is otherwise free to contain, yet a path
    // resolves *against* them instead of into them, so they can't be caught by escaping alone.
    if (this == "." || this == "..") return replace(".", "%2E")
    val bytes = toByteArray(Charsets.UTF_8)
    return buildString(bytes.size) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            val char = Char(value)
            if (char in UNRESERVED) {
                append(char)
            } else {
                append('%')
                append(HEX[value shr 4])
                append(HEX[value and 0xF])
            }
        }
    }
}

/** The `owner/repo` pair as two escaped path segments, the shape nearly every address in this
 *  package is built around. [ExternalApp.repoPath] is this for a source that's already an object. */
internal fun repoPath(owner: String, repo: String): String =
    "${owner.urlPathSegment()}/${repo.urlPathSegment()}"

/**
 * GitLab's REST API names a project by its whole `owner/repo` path squeezed into one path segment,
 * separator included, which is why it can't be built from two [urlPathSegment] calls the way every
 * other provider's is.
 */
internal fun gitlabProjectPath(owner: String, repo: String): String = "$owner/$repo".urlPathSegment()

internal val ExternalApp.gitlabProjectPath: String get() = gitlabProjectPath(owner, repo)

/** Everything RFC 3986 lets a path segment carry unescaped. */
private const val UNRESERVED =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

private const val HEX = "0123456789ABCDEF"

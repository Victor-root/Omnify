package com.looker.droidify.compose.repoEdit

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/** The path endings a repository address conventionally carries, longest first where it matters. */
internal val REPO_ADDRESS_SUFFIXES = listOf("fdroid/repo", "repo")

/**
 * The one line [address] holds, or null when it holds none, or several.
 *
 * A line break is a boundary, never something to tidy away: what sits on the next line is a second
 * thing, not the rest of the first. Joining the two silently made an address out of them that nobody
 * typed, and the field, being a single-line one, showed nothing of it. Two lines therefore mean the
 * address is not usable as it stands, which the screen says in those words.
 */
internal fun singleAddressLine(address: String): String? =
    address.lineSequence().filter { it.isNotBlank() }.toList().singleOrNull()

/**
 * The line with everything in it that cannot be seen taken out, and the whitespace around it dropped.
 *
 * A space between two visible characters stays: the user can see it and correct it, and a real URL
 * escapes its spaces, so one sitting there is a typo worth reporting rather than something to repair
 * behind their back. A character that draws nothing at all is another matter. A byte order mark
 * carried out of a file, a bidi mark picked up off a web page, the space a keyboard adds after a word
 * it has just completed: none of them was typed on purpose, none of them can be found by staring at
 * the field, and [URI] refuses the whole address over any one of them.
 *
 * That refusal is the reported bug. `https://example.org/fdroid/repo` was shown back as an invalid
 * address, with both save buttons greyed out and nothing on screen naming the character responsible.
 */
internal fun String.visibleCharactersOnly(): String =
    filterNot { it.isISOControl() || it.category == CharCategory.FORMAT }.trim()

/**
 * A typed repository address as the single form the rest of the screen works with, or null when it
 * is not an address at all.
 *
 * What comes out is also what gets saved and checked, rather than the raw text: one spelling of an
 * address, with no trailing slash, is what the duplicate check and the suffix probing below both
 * already assume they are comparing.
 */
internal fun normalizeRepoAddress(address: String): String? {
    val line = singleAddressLine(address) ?: return null
    val uri = try {
        URI(line.visibleCharactersOnly()).takeIf { it.isAbsolute }?.normalize()
    } catch (_: URISyntaxException) {
        return null
    } ?: return null
    return try {
        uri.toURL().toURI().toString().removeSuffix("/")
    } catch (_: Exception) {
        null
    }
}

/**
 * What a shared repository link was carrying. Only [address] is ever there: a link names the
 * fingerprint, the login, both or neither, and each field is filled in only when the link holds it,
 * so one that says less simply leaves more of the form to type.
 */
internal data class RepoLink(
    val address: String,
    val fingerprint: String? = null,
    val username: String? = null,
    val password: String? = null,
)

/**
 * The repository a shared link points at, or null when it points at none.
 *
 * Reads the shapes a repository gets passed around in: the `fdroidrepo://` and `fdroidrepos://`
 * schemes F-Droid clients answer to, a plain https address, and an fdroid.link page, which is a
 * redirector carrying the real address in its fragment. The fingerprint travels as a query
 * parameter, as it does everywhere else, and a login as the userinfo any HTTP URL has always been
 * able to carry: `user@host`, or `user:password@host`.
 *
 * The login is taken out of the address rather than left sitting in it: it belongs in the screen's
 * own username and password fields, which is where a repository's stored credentials are read from
 * and where the user can see and change them.
 */
internal fun parseRepoLink(link: String): RepoLink? {
    val text = singleAddressLine(link)?.visibleCharactersOnly()?.asHttpUrl() ?: return null
    val given = try {
        URI(text)
    } catch (_: URISyntaxException) {
        return null
    }
    val uri = given.followFdroidLink() ?: return null
    val host = uri.host ?: return null
    val bare = try {
        URI(uri.scheme, null, host, uri.port, uri.path, null, null)
    } catch (_: URISyntaxException) {
        return null
    }
    val (username, password) = uri.userInfo.asCredentials()
    return RepoLink(
        address = normalizeRepoAddress(bare.toString()) ?: return null,
        fingerprint = uri.rawQuery.queryValue("fingerprint") ?: uri.rawQuery.queryValue("FINGERPRINT"),
        username = username,
        password = password,
    )
}

/**
 * The address behind an fdroid.link page, or [this] when it isn't one.
 *
 * That page exists because `fdroidrepos://` isn't a link anywhere text is merely text: it is an
 * ordinary https page whose fragment holds the address it stands for. Followed once and no further,
 * so a fragment naming another one can't send this round in circles.
 */
private fun URI.followFdroidLink(): URI? {
    if (!isFdroidLink()) return this
    val fragment = rawFragment?.takeIf { it.isNotEmpty() } ?: return null
    val target = try {
        URI(URLDecoder.decode(fragment, "UTF-8").visibleCharactersOnly().asHttpUrl())
    } catch (_: Exception) {
        return null
    }
    // Whatever it leads to, it is not the redirector again: that page is never a repository, so one
    // pointing at another is a link with no repository at the end of it rather than a hop to take.
    return target.takeUnless { it.isFdroidLink() }
}

private fun URI.isFdroidLink(): Boolean = host?.endsWith("fdroid.link") == true

/** The scheme F-Droid clients register, read as the https address it stands for. */
private fun String.asHttpUrl(): String = when {
    startsWith("fdroidrepos://") -> "https://" + removePrefix("fdroidrepos://")
    startsWith("fdroidrepo://") -> "https://" + removePrefix("fdroidrepo://")
    else -> this
}

/** The username and password a `user:password@host` address carries, each null when absent. */
private fun String?.asCredentials(): Pair<String?, String?> {
    val userInfo = this?.takeIf { it.isNotEmpty() } ?: return null to null
    val username = userInfo.substringBefore(':').takeIf { it.isNotEmpty() }
    val password = userInfo.substringAfter(':', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
    return username to password
}

/** The value [name] holds in a raw query string, decoded, or null when the query doesn't hold it. */
private fun String?.queryValue(name: String): String? = this
    ?.split('&')
    ?.firstOrNull { it.substringBefore('=') == name }
    ?.substringAfter('=', missingDelimiterValue = "")
    ?.takeIf { it.isNotEmpty() }
    ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }

/**
 * [address] without the repository path ending it may carry, so that the two spellings of one
 * repository (with and without `/fdroid/repo`) are recognised as the same when checking whether it
 * is already tracked.
 */
internal fun stripRepoPathSuffix(address: String): String {
    val cropped = address.removeSuffix("/")
    val suffix = REPO_ADDRESS_SUFFIXES
        .sortedByDescending { it.length }
        .find { cropped.endsWith("/$it") }
        ?: return cropped
    return cropped.substring(0, cropped.length - suffix.length - 1)
}

/**
 * Every step [normalizeRepoAddress] takes on [address] and what each one gave, as one line.
 *
 * Temporary instrumentation for a refusal nobody can explain from the screen: "invalid address" names
 * nothing, and the character responsible is routinely one that draws nothing at all, so a report can
 * only ever say "it looks fine to me". This says exactly which character sits where, what was taken
 * out of the address before reading it, and which step refused it.
 *
 * Called only from a debug build (see [RepoEditViewModel]), and pure, so it can never be the thing
 * that goes wrong. Remove it once the reports stop.
 */
internal fun repoAddressDiagnosis(address: String): String = buildString {
    append("raw=").append(address.asCodePoints())
    val line = singleAddressLine(address)
    if (line == null) {
        val lines = address.lineSequence().count { it.isNotBlank() }
        append(" | REFUSED: ").append(lines).append(" non-blank line(s), one wanted")
        return@buildString
    }
    val cleaned = line.visibleCharactersOnly()
    if (cleaned == line) {
        append(" | nothing invisible to remove")
    } else {
        append(" | REMOVED ").append(removedFrom(line, cleaned))
        append(" | cleaned=").append(cleaned.asCodePoints())
    }
    val uri = try {
        URI(cleaned)
    } catch (e: URISyntaxException) {
        append(" | URI REFUSED it: reason=").append(e.reason).append(" atIndex=").append(e.index)
        return@buildString
    }
    append(" | scheme=").append(uri.scheme)
    append(" host=").append(uri.host)
    append(" port=").append(uri.port)
    append(" path=").append(uri.path)
    append(" absolute=").append(uri.isAbsolute)
    if (!uri.isAbsolute) {
        append(" | REFUSED: no scheme")
        return@buildString
    }
    val url = try {
        uri.normalize().toURL()
    } catch (e: Exception) {
        append(" | toURL REFUSED it: ").append(e.javaClass.simpleName).append(": ").append(e.message)
        return@buildString
    }
    append(" | url=").append(url)
    append(" | result=").append(normalizeRepoAddress(address))
}

/** The characters [visibleCharactersOnly] took out, so a report says which ones they were. */
private fun removedFrom(raw: String, cleaned: String): String {
    val kept = cleaned.toMutableList()
    return raw
        .filterNot { char -> kept.remove(char) }
        .toList()
        .joinToString(" ") { "U+%04X".format(it.code) }
}

/** Each character as its code point, with the printable ones spelled out beside them. */
private fun String.asCodePoints(): String =
    if (isEmpty()) {
        "(empty)"
    } else {
        "[${length} chars] " + map { char ->
            if (char.code in 0x21..0x7E) char.toString() else "U+%04X".format(char.code)
        }.joinToString("")
    }

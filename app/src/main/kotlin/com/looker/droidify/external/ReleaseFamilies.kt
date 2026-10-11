package com.looker.droidify.external

/**
 * One app among several published in the same release stream of a single repository, told apart by
 * what its tags carry around the version number: a prefix (ente-io/ente's "auth-v4.4.25" next to
 * "photos-v1.2.3") or, failing that, a suffix (bitwarden/android's "v2026.9.1-bwa" next to
 * "v2026.9.1-bwpm").
 */
data class ReleaseFamily(
    /** Readable name: the tag's marker prettified ("Auth", "Photos Desktop"), or the repository's own
     *  name for the family whose tags carry no marker at all. */
    val name: String,
    /** The keyword pinning a source to this family through [ExternalApp.versionIncludeFilter] ("auth-v",
     *  "-bwa"). Null for the unmarked family, which can only be reached by excluding the others. */
    val keyword: String?,
    /** The family's newest release, shown so the user can recognise the app by its tag and APK. */
    val latest: Release,
)

/**
 * Groups [releases] (newest first) by the app they belong to, in order of each app's newest release.
 * A release whose tag carries no version number is left out: a moving tag such as "nightly" says
 * nothing about which app it is. A single family back means a regular, single-app repository.
 */
fun releaseFamilies(releases: List<Release>, unmarkedName: String): List<ReleaseFamily> {
    val families = LinkedHashMap<String?, ReleaseFamily>()
    for (release in releases) {
        val marker = tagMarker(release.tag) ?: continue
        val key = marker.keyword?.lowercase()
        if (key in families) continue
        families[key] = ReleaseFamily(
            name = marker.name?.let(::prettifyRepoName) ?: unmarkedName,
            keyword = marker.keyword,
            latest = release,
        )
    }
    return families.values.toList()
}

/**
 * The (include, exclude) keyword lists pinning a source to [family], one of this list, starting from
 * what the user already typed: a marked family replaces the include list with its own keyword, and the
 * unmarked one keeps it and excludes every other family's keyword instead.
 */
fun List<ReleaseFamily>.keywordFiltersFor(
    family: ReleaseFamily,
    include: String,
    exclude: String,
): Pair<String, String> {
    family.keyword?.let { return it to exclude }
    val excluded = listOf(exclude.trim()).filter { it.isNotEmpty() } + mapNotNull { it.keyword }
    return include to excluded.joinToString(", ")
}

/** What a tag carries besides its version: [name] and [keyword] are both null for an unmarked tag. */
private class TagMarker(val name: String?, val keyword: String?)

private val UNMARKED = TagMarker(name = null, keyword = null)

/**
 * What tells this tag's app apart: the text before the version number (keyword kept up to the version's
 * own "v", so "photos-v" doesn't also match "photos-desktop-v"), else a word after it that isn't a
 * pre-release qualifier ("-bwa", but not "-rc" or "-beta.2"). [UNMARKED] when there is neither, null
 * when the tag carries no version number at all.
 */
private fun tagMarker(tag: String): TagMarker? {
    val version = VERSION_IN_TAG.find(tag) ?: return null
    val rawPrefix = tag.substring(0, version.range.first)
    // A lone "v" is the version's own marker cut off by a separator ("v.2.5.5"), not an app's name.
    val prefixName = rawPrefix.trim(*SEPARATORS).takeUnless { it.equals("v", ignoreCase = true) }.orEmpty()
    if (prefixName.isNotEmpty() && prefixName.lowercase() !in NEUTRAL_PREFIXES) {
        val versionV = version.value.takeWhile { it == 'v' || it == 'V' }
        return TagMarker(prefixName, rawPrefix + versionV)
    }
    val suffix = tag.substring(version.range.last + 1)
    val token = suffix.split(*SEPARATORS)
        .firstOrNull { it.isNotEmpty() && !it.all(Char::isDigit) && !PRERELEASE_QUALIFIER.matches(it) }
        ?: return UNMARKED
    val at = suffix.indexOf(token)
    val keyword = if (at > 0) suffix.substring(at - 1, at + token.length) else token
    return TagMarker(token, keyword)
}

/** A version number, optionally "v"-prefixed, not glued to a word before it ("dev1.2" is no "v1.2"). */
private val VERSION_IN_TAG = Regex("""(?<![A-Za-z0-9])[vV]?\d+(?:\.\d+)*""")

private val SEPARATORS = charArrayOf('-', '_', '.', '+', '/', ' ')

/** Prefixes naming no app, only the act of releasing ("release-1.2" is the same app as "v1.3"). */
private val NEUTRAL_PREFIXES = setOf("release", "rel", "version", "ver", "tag", "build", "stable")

/** Suffix words qualifying a build of the same app rather than naming another one. */
private val PRERELEASE_QUALIFIER = Regex(
    "(rc|beta|alpha|a|b|pre|preview|dev|snapshot|nightly|canary|test|hotfix|fix|patch|build|release|" +
        "final|stable|r)\\d*",
    RegexOption.IGNORE_CASE,
)

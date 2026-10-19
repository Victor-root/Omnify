package com.looker.droidify.external

/**
 * One app among several published by a single repository. Told apart either by what its tags carry
 * around the version number, when each release belongs to one app (ente-io/ente's "auth-v4.4.25" next
 * to "photos-v1.2.3", bitwarden/android's "v2026.9.1-bwa" next to "v2026.9.1-bwpm"), or by the name of
 * its APK, when every release carries several apps at once (nullcpy/rvb's
 * "meesho-debakarr-v29.6-arm64-v8a.apk" next to "discord-venus-v348.10-Stable-arm64-v8a.apk").
 */
data class ReleaseFamily(
    /** Readable name: the tag's marker or the APK's name prettified ("Auth", "Photos Desktop",
     *  "Meesho Debakarr"), or the repository's own name for the family whose tags carry no marker. */
    val name: String,
    /** The keyword pinning a source to this family through [ExternalApp.versionIncludeFilter] ("auth-v",
     *  "-bwa"). Null for the unmarked family, which can only be reached by excluding the others, and for
     *  a family told apart by its APK name. */
    val keyword: String?,
    /** The family's newest release, shown so the user can recognise the app by its tag and APK. */
    val latest: Release,
    /** The [ExternalApp.apkFilter] pinning a source to this family, for one told apart by its APK name
     *  ("^meesho-debakarr[-_]v?\d"). Null for a family told apart by its tags. */
    val apkFilter: String? = null,
) {
    /** The [ExternalApp.variant] a source following this family gets. */
    val variant: String get() = variantOf(name)
}

/**
 * The apps [releases] (newest first) belong to: told apart by their tags when those name more than one
 * ([releaseFamilies]), else by their APK names ([apkFamilies]). A single family, or none, back means a
 * regular, single-app repository.
 */
fun appFamilies(releases: List<Release>, unmarkedName: String): List<ReleaseFamily> =
    releaseFamilies(releases, unmarkedName).takeIf { it.size > 1 } ?: apkFamilies(releases)

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
 * Groups the APKs of [releases] (newest first) by the app they are, for a repository whose every release
 * carries several apps side by side. An APK is known by the text before its version number, minus any
 * architecture, build-type or store-flavour word ("meesho-debakarr-v29.6-arm64-v8a.apk" is
 * "meesho-debakarr", "MyApp-fdroid-1.2.apk" is "MyApp"), so the builds of one app stay one app. An APK
 * whose name carries no version is left out: "app-release.apk" says nothing about which app it is.
 *
 * Empty unless at least one release carries two different apps at once: a project that renamed its APK
 * at some point has two names across its history, never side by side, and is still a single app.
 */
fun apkFamilies(releases: List<Release>): List<ReleaseFamily> {
    class Found(val stem: String, val latest: Release, val extraWords: MutableSet<String>)
    val found = LinkedHashMap<String, Found>()
    var sideBySide = false
    for (release in releases) {
        val names = release.assets
            .filter { it.name.endsWith(".apk", ignoreCase = true) }
            .mapNotNull { apkName(it.name) }
        if (names.distinctBy { it.stem.lowercase() }.size > 1) sideBySide = true
        for (name in names) {
            found.getOrPut(name.stem.lowercase()) { Found(name.stem, release, mutableSetOf()) }
                .extraWords += name.extraWords
        }
    }
    if (!sideBySide || found.size < 2) return emptyList()
    return found.values.map {
        // The words seen between the name and the version are allowed there, and nothing else: so
        // "instagram-hushgram" doesn't also take "instagram-hushgram-clone-v451.apk".
        val extra = it.extraWords.sorted().takeIf { words -> words.isNotEmpty() }
            ?.joinToString("|", prefix = "(?:[-_](?:", postfix = "))*") { word -> word.escapedForRegex() }
            .orEmpty()
        ReleaseFamily(
            name = prettifyRepoName(it.stem),
            keyword = null,
            latest = it.latest,
            apkFilter = "^${it.stem.escapedForRegex()}$extra[-_]v?\\d",
        )
    }
}

/** An APK's file name split into what names the app ([stem], as written) and the architecture,
 *  build-type or flavour words after it and before the version ([extraWords], lower case). */
private class ApkName(val stem: String, val extraWords: List<String>)

/** [ApkName] of [fileName], or null when it carries no version or nothing but such words before it. */
private fun apkName(fileName: String): ApkName? {
    val version = VERSION_IN_APK_NAME.find(fileName) ?: return null
    val prefix = fileName.substring(0, version.range.first)
    val words = NAME_WORD.findAll(prefix).toList()
    val kept = words.indexOfLast { !it.value.isBuildWord() } + 1
    if (kept == 0) return null
    return ApkName(
        stem = prefix.substring(0, words[kept - 1].range.last + 1),
        extraWords = words.drop(kept).map { it.value.lowercase() },
    )
}

/** A version in an APK name: separated from the name before it, "v"-prefixed or not, with at least one
 *  dot, so an architecture such as "x86_64" or "arm64" is never taken for one. */
private val VERSION_IN_APK_NAME = Regex("""(?<=[-_])[vV]?\d+(?:\.\d+)+""")

private val NAME_WORD = Regex("[^-_]+")

private fun String.isBuildWord(): Boolean = lowercase().let { it in BUILD_WORDS || it in ABI_WORDS }

/** Words telling builds of one app apart rather than naming it: build types and store flavours. */
private val BUILD_WORDS = setOf(
    "release", "debug", "signed", "unsigned", "universal", "app", "apk", "android", "all", "noarch",
    "fdroid", "foss", "libre", "play", "gplay", "googleplay", "github", "store", "gms", "nogms", "huawei",
)

private val ABI_WORDS = setOf("arm64", "v8a", "arm", "v7a", "armeabi", "x86", "64", "aarch64", "armv7")

/** [this] as a regular expression matching itself: a backslash before anything that is not a letter, a
 *  digit, a hyphen or an underscore, readable in the filter field where it is shown ("^meesho-debakarr"
 *  stays as is). */
private fun String.escapedForRegex(): String = buildString {
    for (char in this@escapedForRegex) {
        if (!char.isLetterOrDigit() && char != '-' && char != '_') append('\\')
        append(char)
    }
}

/**
 * The (apk, include, exclude) filters pinning a source to [family], one of this list, starting from
 * what the user already typed: a family told apart by its APK name replaces the APK filter with its own,
 * a marked family replaces the include list with its own keyword, and the unmarked one keeps it and
 * excludes every other family's keyword instead.
 */
fun List<ReleaseFamily>.filtersFor(
    family: ReleaseFamily,
    apk: String,
    include: String,
    exclude: String,
): Triple<String, String, String> {
    family.apkFilter?.let { return Triple(it, include, exclude) }
    family.keyword?.let { return Triple(apk, it, exclude) }
    val excluded = listOf(exclude.trim()).filter { it.isNotEmpty() } + mapNotNull { it.keyword }
    return Triple(apk, include, excluded.joinToString(", "))
}

/**
 * Whether [app], a source of the same repository, already follows [family]: by its [ExternalApp.variant],
 * or, for a source added before variants existed, by the filter that pins it there.
 */
fun ReleaseFamily.isFollowedBy(app: ExternalApp): Boolean = when {
    app.variant.isNotEmpty() -> app.variant == variant
    apkFilter != null -> app.apkFilter == apkFilter
    keyword != null -> app.versionIncludeFilter.equals(keyword, ignoreCase = true)
    else -> false
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

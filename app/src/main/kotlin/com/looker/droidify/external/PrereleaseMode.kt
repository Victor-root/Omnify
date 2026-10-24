package com.looker.droidify.external

import kotlinx.serialization.Serializable

/**
 * What an external source does with its repository's pre-releases, chosen on the app's own page: whether
 * they are listed among its versions, and whether one is ever offered as the version to install or
 * update to.
 *
 * A repository publishing nothing but pre-releases has no choice to make: they are listed and offered
 * whatever this says, marked as pre-releases ([onlyPrereleases]).
 */
@Serializable
enum class PrereleaseMode {
    /** Neither listed nor offered. */
    HIDDEN,

    /** Listed and installable by hand, never offered: updates stay on stable releases. */
    SHOWN,

    /** Listed, and the newest release of either kind is offered. */
    BOTH,

    /** Listed, and only pre-releases are offered. */
    ONLY,
    ;

    val listsPrereleases: Boolean
        get() = this != HIDDEN

    /** Whether [release] may be offered as the version to install under this mode, [onlyPrereleases]
     *  being true when the repository publishes nothing else. */
    fun offers(release: Release, onlyPrereleases: Boolean): Boolean = when {
        onlyPrereleases || this == BOTH -> true
        this == ONLY -> release.isPrerelease
        else -> !release.isPrerelease
    }

    /** Whether [release] shows in the app's version list under this mode (see [offers]). */
    fun lists(release: Release, onlyPrereleases: Boolean): Boolean =
        onlyPrereleases || listsPrereleases || !release.isPrerelease
}

/** Whether these releases are all pre-releases: a repository with no stable release to fall back on. */
fun List<Release>.onlyPrereleases(): Boolean = isNotEmpty() && all { it.isPrerelease }

/** Whether these releases hold both pre-releases and stable ones: the only case where choosing between
 *  them means anything. */
fun List<Release>.mixesPrereleases(): Boolean = any { it.isPrerelease } && any { !it.isPrerelease }

/**
 * Of [this] (newest first), the one with the highest version: the newest, unless one published before it
 * carries a higher version number. With stable and pre-releases mixed, a fix to the stable line often
 * comes out after a pre-release of the next version (2.0-beta1, then 1.9.1), and offering the newest by
 * date would take someone on the pre-release back down. Releases whose tag carries no version number are
 * left to their date order.
 */
fun List<Release>.highestVersion(): Release? {
    val newest = firstOrNull() ?: return null
    val newestVersion = dottedVersionOrNull(newest.tag) ?: return newest
    var best = newest
    var bestVersion = newestVersion
    for (release in this) {
        val version = dottedVersionOrNull(release.tag) ?: continue
        if (compareVersionStrings(version, bestVersion) > 0) {
            best = release
            bestVersion = version
        }
    }
    return best
}

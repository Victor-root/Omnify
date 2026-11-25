package com.looker.droidify.data

import com.looker.droidify.data.model.Package
import com.looker.droidify.external.PrereleaseMode

/**
 * Whether these builds of an app, from every repository offering it, hold both betas and stable
 * releases: the only case where its pre-release choice changes anything. An app published as betas
 * only, or as stable releases only, has all of them listed and offered whatever the choice.
 */
fun <T> List<Pair<Package, T>>.mixesPrereleases(): Boolean =
    any { (pkg, _) -> pkg.isPrerelease } && any { (pkg, _) -> !pkg.isPrerelease }

/** The builds of [this] (an app's, see [mixesPrereleases]) its pre-release choice [mode] shows in its list. */
fun <T> List<Pair<Package, T>>.listedUnder(mode: PrereleaseMode): List<Pair<Package, T>> =
    if (mixesPrereleases()) filter { (pkg, _) -> mode.lists(isPrerelease = pkg.isPrerelease) } else this

/** The builds of [this] (an app's, see [mixesPrereleases]) its pre-release choice [mode] may install. */
fun <T> List<Pair<Package, T>>.offeredUnder(mode: PrereleaseMode): List<Pair<Package, T>> =
    if (mixesPrereleases()) filter { (pkg, _) -> mode.offers(isPrerelease = pkg.isPrerelease) } else this

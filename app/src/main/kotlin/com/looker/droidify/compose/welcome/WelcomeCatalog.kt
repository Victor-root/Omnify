package com.looker.droidify.compose.welcome

import androidx.annotation.StringRes
import com.looker.droidify.R
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.model.Repository

/** A repository the welcome page has a line of its own for. [address] is without its trailing slash, as
 *  in [Repository.defaultAddresses]. */
internal data class DescribedRepository(
    val address: String,
    @param:StringRes val description: Int,
)

/**
 * What the welcome page lists and in which order. Every repository Omnify ships is offered; the few
 * general-purpose ones that have a line written for this page come first, in the order below, and the
 * rest follow in Omnify's own order, each with the description its index gives. The three sources a new
 * install has always switched on (Omnify's own update channel, F-Droid and IzzyOnDroid) start ticked,
 * everything else does not.
 */
internal object WelcomeCatalog {
    private const val FDROID = "https://f-droid.org/repo"
    private const val IZZY = "https://apt.izzysoft.de/fdroid/repo"

    val describedRepositories: List<DescribedRepository> = listOf(
        DescribedRepository(FDROID, R.string.welcome_source_fdroid),
        DescribedRepository(IZZY, R.string.welcome_source_izzy),
        DescribedRepository("https://f-droid.org/archive", R.string.welcome_source_fdroid_archive),
        DescribedRepository("https://guardianproject.info/fdroid/repo", R.string.welcome_source_guardian),
        DescribedRepository("https://microg.org/fdroid/repo", R.string.welcome_source_microg),
    )

    /** Every repository Omnify ships: the described ones first, then the others as Omnify lists them. */
    val repositoryOrder: List<String> = describedRepositories.map { it.address } +
        (Repository.defaultAddresses - describedRepositories.map { it.address }.toSet())

    fun description(address: String): Int? =
        describedRepositories.firstOrNull { it.address == address }?.description

    /** What is ticked before anything is touched, as the list [WelcomeViewModel] keeps its selection in
     *  (an [ArrayList], the form a saved state can hold). */
    fun defaultChecked(): ArrayList<String> = arrayListOf(ExternalApp.OMNIFY_REPO_KEY, FDROID, IZZY)
}

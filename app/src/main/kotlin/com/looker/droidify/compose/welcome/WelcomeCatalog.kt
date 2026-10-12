package com.looker.droidify.compose.welcome

import androidx.annotation.StringRes
import com.looker.droidify.R
import com.looker.droidify.external.ExternalApp

/**
 * A source the welcome page offers. [id] is the address of a repository (without its trailing slash,
 * as in [com.looker.droidify.model.Repository.defaultAddresses]), or the key of an external source.
 */
internal data class RecommendedSource(
    val id: String,
    @StringRes val description: Int,
    val checkedByDefault: Boolean,
)

/**
 * The sources the welcome page lists, in its order. A handful of the general-purpose ones Omnify ships,
 * not all of them: the rest stay one switch away in Repositories. The three Omnify has always switched
 * on for a new install start ticked, the others do not.
 */
internal object WelcomeCatalog {
    val sources: List<RecommendedSource> = listOf(
        RecommendedSource(ExternalApp.OMNIFY_REPO_KEY, R.string.welcome_source_omnify, true),
        RecommendedSource("https://f-droid.org/repo", R.string.welcome_source_fdroid, true),
        RecommendedSource("https://apt.izzysoft.de/fdroid/repo", R.string.welcome_source_izzy, true),
        RecommendedSource("https://f-droid.org/archive", R.string.welcome_source_fdroid_archive, false),
        RecommendedSource(
            "https://guardianproject.info/fdroid/repo",
            R.string.welcome_source_guardian,
            false,
        ),
        RecommendedSource("https://microg.org/fdroid/repo", R.string.welcome_source_microg, false),
    )

    /** What is ticked before the user touches anything, as the list [WelcomeViewModel] keeps its
     *  selection in (an [ArrayList], the form a saved state can hold). */
    fun defaultChecked(): ArrayList<String> =
        ArrayList(sources.filter { it.checkedByDefault }.map { it.id })
}

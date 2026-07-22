package com.looker.droidify.compose.repoList.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navOptions
import com.looker.droidify.compose.repoList.RepoListScreen
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.compose.tv.TvRepoListScreen
import kotlinx.serialization.Serializable

@Serializable
object RepoList

fun NavController.navigateToRepoList() {
    this.navigate(
        RepoList,
        navOptions {
            launchSingleTop = true
            restoreState = true
        },
    )
}

fun NavGraphBuilder.repoList(
    onRepoClick: (Int) -> Unit,
    onBackClick: () -> Unit,
    onAddRepo: () -> Unit,
    onAccountClick: (String) -> Unit,
    onSourceClick: (String) -> Unit,
) {
    composable<RepoList> {
        // Android TV gets its own reskinned source-management screen; the phone screen is untouched.
        if (LocalIsTelevision.current) {
            TvRepoListScreen(
                onRepoClick = onRepoClick,
                onBackClick = onBackClick,
                onAddRepo = onAddRepo,
                onAccountClick = onAccountClick,
                onSourceClick = onSourceClick,
                viewModel = hiltViewModel(),
            )
        } else {
            RepoListScreen(
                onRepoClick = onRepoClick,
                onBackClick = onBackClick,
                onAddRepo = onAddRepo,
                onAccountClick = onAccountClick,
                onSourceClick = onSourceClick,
                viewModel = hiltViewModel(),
            )
        }
    }
}

package com.looker.droidify.compose.repoDetail.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.composable
import androidx.navigation.navOptions
import com.looker.droidify.compose.repoDetail.RepoDetailScreen
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.compose.tv.TvRepoDetailScreen
import kotlinx.serialization.Serializable

@Serializable
data class RepoDetail(val repoId: Int)

fun NavController.navigateToRepoDetail(repoId: Int) {
    this.navigate(
        RepoDetail(repoId),
        navOptions {
            launchSingleTop = true
        },
    )
}

fun NavGraphBuilder.repoDetail(
    onBackClick: () -> Unit,
    onEditClick: (Int) -> Unit,
    onAppClick: (String) -> Unit,
) {
    composable<RepoDetail> { backStackEntry ->
        // Android TV gets a single-page detail (no tab row); the phone screen is untouched.
        if (LocalIsTelevision.current) {
            TvRepoDetailScreen(
                onBackClick = onBackClick,
                onEditClick = onEditClick,
                onAppClick = onAppClick,
                viewModel = hiltViewModel(),
            )
        } else {
            RepoDetailScreen(
                onBackClick = onBackClick,
                onEditClick = onEditClick,
                onAppClick = onAppClick,
            )
        }
    }
}

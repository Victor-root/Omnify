package com.looker.droidify.compose.settings.exploreCarousels.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navOptions
import com.looker.droidify.compose.settings.exploreCarousels.ExploreCarouselsScreen
import kotlinx.serialization.Serializable

@Serializable
object ExploreCarousels

fun NavController.navigateToExploreCarousels() {
    this.navigate(
        ExploreCarousels,
        navOptions {
            launchSingleTop = true
            restoreState = true
        },
    )
}

fun NavGraphBuilder.exploreCarousels(onBackClick: () -> Unit) {
    composable<ExploreCarousels> {
        ExploreCarouselsScreen(
            viewModel = hiltViewModel(),
            onBackClick = onBackClick,
        )
    }
}

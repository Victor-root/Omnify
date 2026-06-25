package com.looker.droidify.compose.externalApps.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navOptions
import com.looker.droidify.compose.externalApps.ExternalAppsScreen
import kotlinx.serialization.Serializable

@Serializable
object ExternalApps

fun NavController.navigateToExternalApps() {
    this.navigate(
        ExternalApps,
        navOptions {
            launchSingleTop = true
            restoreState = true
        },
    )
}

fun NavGraphBuilder.externalApps(onBackClick: () -> Unit) {
    composable<ExternalApps> {
        ExternalAppsScreen(viewModel = hiltViewModel(), onBackClick = onBackClick)
    }
}

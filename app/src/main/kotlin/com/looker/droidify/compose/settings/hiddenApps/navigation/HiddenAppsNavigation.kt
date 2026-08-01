package com.looker.droidify.compose.settings.hiddenApps.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navOptions
import com.looker.droidify.compose.settings.hiddenApps.HiddenAppsScreen
import kotlinx.serialization.Serializable

@Serializable
object HiddenApps

fun NavController.navigateToHiddenApps() {
    this.navigate(
        HiddenApps,
        navOptions {
            launchSingleTop = true
            restoreState = true
        },
    )
}

fun NavGraphBuilder.hiddenApps(onBackClick: () -> Unit) {
    composable<HiddenApps> {
        HiddenAppsScreen(
            viewModel = hiltViewModel(),
            onBackClick = onBackClick,
        )
    }
}

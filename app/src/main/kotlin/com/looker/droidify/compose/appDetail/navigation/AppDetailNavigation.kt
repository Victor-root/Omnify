package com.looker.droidify.compose.appDetail.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navOptions
import com.looker.droidify.compose.appDetail.AppDetailScreen
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.compose.tv.TvAppDetailScreen
import kotlinx.serialization.Serializable

@Serializable
data class AppDetail(val packageName: String)

fun NavController.navigateToAppDetail(packageName: String) {
    this.navigate(
        AppDetail(packageName),
        navOptions {
            launchSingleTop = true
        },
    )
}

fun NavGraphBuilder.appDetail(
    onBackClick: () -> Unit,
) {
    composable<AppDetail> {
        // Android TV gets its own lean, D-pad-first detail screen; the phone screen is untouched and
        // simply isn't composed there. Both read the same ViewModel.
        if (LocalIsTelevision.current) {
            TvAppDetailScreen(
                onBackClick = onBackClick,
                viewModel = hiltViewModel(),
            )
        } else {
            AppDetailScreen(
                onBackClick = onBackClick,
                viewModel = hiltViewModel(),
            )
        }
    }
}

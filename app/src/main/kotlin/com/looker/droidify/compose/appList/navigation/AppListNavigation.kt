package com.looker.droidify.compose.appList.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navOptions
import com.looker.droidify.compose.appList.AppListScreen
import com.looker.droidify.compose.appList.AppListViewModel
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.compose.tv.TvHomeScreen
import kotlinx.serialization.Serializable

@Serializable
object AppList

fun NavController.navigateToAppList() {
    this.navigate(
        AppList,
        navOptions {
            launchSingleTop = true
            restoreState = true
        },
    )
}

fun NavGraphBuilder.appList(
    onAppClick: (String) -> Unit = { _ -> },
    onExternalAppClick: (String) -> Unit = { _ -> },
    onNavigateToRepos: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    composable<AppList> {
        val viewModel: AppListViewModel = hiltViewModel()
        // Android TV gets an entirely separate, Google-Play-TV-style home built for a D-pad; the phone
        // screen is left untouched and simply isn't composed there. Both read the same ViewModel.
        if (LocalIsTelevision.current) {
            TvHomeScreen(
                viewModel = viewModel,
                onAppClick = onAppClick,
                onExternalAppClick = onExternalAppClick,
                onNavigateToRepos = onNavigateToRepos,
                onNavigateToSettings = onNavigateToSettings,
            )
        } else {
            AppListScreen(
                onAppClick = onAppClick,
                onExternalAppClick = onExternalAppClick,
                viewModel = viewModel,
                onNavigateToRepos = onNavigateToRepos,
                onNavigateToSettings = onNavigateToSettings,
            )
        }
    }
}

package com.looker.droidify.compose.settings.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navOptions
import androidx.navigation.toRoute
import com.looker.droidify.compose.settings.SettingsScreen
import kotlinx.serialization.Serializable

/** [highlightGithubToken] is true when navigating here specifically to fix a rejected GitHub token (a
 *  warning banner elsewhere was tapped) — the screen scrolls straight to that field and pulses it,
 *  instead of leaving the user to find it themselves in a long settings list. */
@Serializable
data class Settings(val highlightGithubToken: Boolean = false)

fun NavController.navigateToSettings(highlightGithubToken: Boolean = false) {
    navigate(
        Settings(highlightGithubToken),
        navOptions {
            launchSingleTop = true
            restoreState = true
        },
    )
}

fun NavGraphBuilder.settings(
    onBackClick: () -> Unit,
    onOpenEasterEgg: () -> Unit,
    onNavigateToHiddenApps: () -> Unit,
) {
    composable<Settings> { backStackEntry ->
        val route = backStackEntry.toRoute<Settings>()
        SettingsScreen(
            viewModel = hiltViewModel(),
            onBackClick = onBackClick,
            onOpenEasterEgg = onOpenEasterEgg,
            onNavigateToHiddenApps = onNavigateToHiddenApps,
            highlightGithubToken = route.highlightGithubToken,
        )
    }
}

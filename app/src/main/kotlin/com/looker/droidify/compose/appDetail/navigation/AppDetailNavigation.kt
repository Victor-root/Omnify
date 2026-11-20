package com.looker.droidify.compose.appDetail.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.looker.droidify.compose.appDetail.AppDetailScreen
import com.looker.droidify.compose.theme.AccentScreen
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.compose.tv.TvAppDetailScreen
import kotlinx.serialization.Serializable

@Serializable
data class AppDetail(val packageName: String)

/** Opens [packageName]'s page, unless it is the one already on top. launchSingleTop can't say that: every
 *  app's page is the same destination, so a link to another app, opened from an app's page, kept that
 *  first page on screen. */
fun NavController.navigateToAppDetail(packageName: String) {
    val top = currentBackStackEntry?.takeIf { it.destination.hasRoute<AppDetail>() }?.toRoute<AppDetail>()
    if (top?.packageName == packageName) return
    navigate(AppDetail(packageName))
}

fun NavGraphBuilder.appDetail(
    onBackClick: () -> Unit,
    onCategoryClick: (String) -> Unit,
) {
    composable<AppDetail> {
        AccentScreen {
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
                    onCategoryClick = onCategoryClick,
                    viewModel = hiltViewModel(),
                )
            }
        }
    }
}

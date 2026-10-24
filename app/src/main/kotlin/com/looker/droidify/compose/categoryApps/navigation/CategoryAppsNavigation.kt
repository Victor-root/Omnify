package com.looker.droidify.compose.categoryApps.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navOptions
import com.looker.droidify.compose.categoryApps.CategoryAppsScreen
import kotlinx.serialization.Serializable

/** Every app of the category [defaultName], opened from an app page's category tags. */
@Serializable
data class CategoryApps(val defaultName: String)

fun NavController.navigateToCategoryApps(defaultName: String) {
    this.navigate(
        CategoryApps(defaultName),
        navOptions {
            launchSingleTop = true
        },
    )
}

fun NavGraphBuilder.categoryApps(
    onBackClick: () -> Unit,
    onAppClick: (String) -> Unit,
) {
    composable<CategoryApps> {
        CategoryAppsScreen(
            viewModel = hiltViewModel(),
            onBackClick = onBackClick,
            onAppClick = onAppClick,
        )
    }
}

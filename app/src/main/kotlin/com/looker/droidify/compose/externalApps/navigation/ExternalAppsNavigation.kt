package com.looker.droidify.compose.externalApps.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.looker.droidify.compose.externalApps.ExternalAccountDetailScreen
import com.looker.droidify.compose.externalApps.ExternalAppDetailScreen
import kotlinx.serialization.Serializable

/** Detail screen for a single tracked external app, addressed by its [ExternalApp.key]. */
@Serializable
data class ExternalAppDetail(val appKey: String)

fun NavController.navigateToExternalAppDetail(appKey: String) {
    this.navigate(ExternalAppDetail(appKey))
}

fun NavGraphBuilder.externalAppDetail(onBackClick: () -> Unit) {
    composable<ExternalAppDetail> { backStackEntry ->
        val route = backStackEntry.toRoute<ExternalAppDetail>()
        ExternalAppDetailScreen(
            appKey = route.appKey,
            viewModel = hiltViewModel(),
            onBackClick = onBackClick,
        )
    }
}

/** Detail screen for a whole-account external source, addressed by its [ExternalAccount.key]. Shows the
 *  account's info and the list of apps discovered from it. */
@Serializable
data class ExternalAccountDetail(val accountKey: String)

fun NavController.navigateToExternalAccountDetail(accountKey: String) {
    this.navigate(ExternalAccountDetail(accountKey))
}

fun NavGraphBuilder.externalAccountDetail(
    onBackClick: () -> Unit,
    onAppClick: (String) -> Unit,
) {
    composable<ExternalAccountDetail> { backStackEntry ->
        val route = backStackEntry.toRoute<ExternalAccountDetail>()
        ExternalAccountDetailScreen(
            accountKey = route.accountKey,
            viewModel = hiltViewModel(),
            onBackClick = onBackClick,
            onAppClick = onAppClick,
        )
    }
}

package com.looker.droidify.compose.welcome.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navOptions
import com.looker.droidify.compose.appList.navigation.AppList
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.compose.tv.TvWelcomeScreen
import com.looker.droidify.compose.welcome.WelcomeScreen
import com.looker.droidify.compose.welcome.WelcomeViewModel
import kotlinx.serialization.Serializable

@Serializable
object Welcome

/** Leaves the welcome page for the home. The page is dropped from the back stack: going back from the
 *  home must close the app, not bring a page that has already done its job back. */
fun NavController.navigateFromWelcomeToAppList() {
    navigate(
        AppList,
        navOptions {
            popUpTo<Welcome> { inclusive = true }
        },
    )
}

fun NavGraphBuilder.welcome(onDone: () -> Unit) {
    composable<Welcome> {
        val viewModel: WelcomeViewModel = hiltViewModel()
        // Android TV gets its own layout, built for a D-pad; the phone screen simply isn't composed there.
        if (LocalIsTelevision.current) {
            TvWelcomeScreen(viewModel = viewModel, onDone = onDone)
        } else {
            WelcomeScreen(viewModel = viewModel, onDone = onDone)
        }
    }
}

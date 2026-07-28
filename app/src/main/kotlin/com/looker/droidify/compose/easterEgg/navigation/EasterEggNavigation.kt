package com.looker.droidify.compose.easterEgg.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navOptions
import com.looker.droidify.compose.easterEgg.EasterEggScreen
import kotlinx.serialization.Serializable

@Serializable
object EasterEgg

fun NavController.navigateToEasterEgg() {
    navigate(
        EasterEgg,
        navOptions {
            launchSingleTop = true
        },
    )
}

fun NavGraphBuilder.easterEgg(onBackClick: () -> Unit) {
    composable<EasterEgg> {
        EasterEggScreen(onBackClick = onBackClick)
    }
}

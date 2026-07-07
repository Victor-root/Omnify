package com.looker.droidify.compose.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.looker.droidify.R
import kotlinx.coroutines.launch

/**
 * A FAB that appears once [scrollState] has scrolled a fair distance down and smooth-scrolls it
 * back to the top on tap. Shared by the F-Droid and external app detail screens: both are a single
 * long column (a tall header card, then a description/README below it) where jumping back to the
 * header without a manual long swipe is worth offering.
 */
@Composable
fun ScrollToTopFab(scrollState: ScrollState) {
    val coroutineScope = rememberCoroutineScope()
    AnimatedVisibility(
        visible = scrollState.value > 800,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
    ) {
        FloatingActionButton(
            onClick = { coroutineScope.launch { scrollState.animateScrollTo(0) } },
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.scroll_to_top),
            )
        }
    }
}

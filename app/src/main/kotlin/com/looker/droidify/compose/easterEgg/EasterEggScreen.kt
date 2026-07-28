package com.looker.droidify.compose.easterEgg

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.components.BackButton
import com.looker.droidify.compose.components.FloatingAppCardsBackground
import com.looker.droidify.compose.components.TvOverscan
import com.looker.droidify.compose.components.tvFocusScale
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.compose.tv.TvAccentBackground
import com.looker.droidify.compose.tv.TvBackButton
import kotlinx.coroutines.delay

/**
 * A hidden, purely-for-fun full-screen reveal: repeatedly tapping the version row in Settings (see
 * SettingsScreen's own VersionFooter) lands here instead of anywhere useful, mirroring Android's own
 * "tap the build number" secret. Nothing here is discoverable any other way, and nothing it shows
 * carries real information beyond what the version row already did.
 */
@Composable
fun EasterEggScreen(onBackClick: () -> Unit) {
    val isTelevision = LocalIsTelevision.current
    val haptic = LocalHapticFeedback.current

    // Played once on arrival: the logo grows in with a bouncy overshoot and settles out of a small tilt.
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    val entranceScale by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "easterEggEntranceScale",
    )
    val entranceRotation by animateFloatAsState(
        targetValue = if (revealed) 0f else -24f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "easterEggEntranceRotation",
    )

    // Idle, forever: a gentle breathing pulse. A continuous spin was the obvious first idea, but the
    // launcher glyph is a directional face (eyes, antenna, body) with an obvious right-way-up, so
    // spinning it would cycle through sideways and upside-down forever instead of reading as alive.
    val breathing = rememberInfiniteTransition(label = "easterEggBreathing")
    val breathingScale by breathing.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "easterEggBreathingScale",
    )

    // A small bonus: tapping the already-revealed logo adds one more full spin on top, purely additive,
    // repeatable forever, with no counter or state to remember afterwards.
    var spinTarget by remember { mutableStateOf(0f) }
    val spin = remember { Animatable(0f) }
    LaunchedEffect(spinTarget) {
        spin.animateTo(spinTarget, animationSpec = tween(600, easing = FastOutSlowInEasing))
    }

    // TV must always hold focus somewhere; the back button is the only other focusable element here.
    val backFocus = remember { FocusRequester() }
    if (isTelevision) {
        LaunchedEffect(Unit) {
            repeat(20) {
                if (runCatching { backFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(50)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (isTelevision) {
            TvAccentBackground()
        } else {
            // A little stronger than the phone default: this screen is almost entirely empty (just the
            // logo and two lines of text), so the wash covers far more visible canvas than usual.
            FloatingAppCardsBackground(intensity = 1.4f)
        }

        if (isTelevision) {
            TvBackButton(
                onBackClick = onBackClick,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(TvOverscan)
                    .focusRequester(backFocus),
            )
        } else {
            BackButton(
                onClick = onBackClick,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            )
        }

        Column(
            modifier = Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val logoSize = if (isTelevision) 280.dp else 240.dp
            Image(
                // A vector rendering of the app's own logo (assets/omnify.svg in the repo), not the
                // launcher's adaptive-icon foreground layer: that one is a raster PNG capped at 432px,
                // which showed clearly blurry once stretched up to this screen's much bigger display
                // size. This is crisp at any size.
                painter = painterResource(R.drawable.ic_omnify_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(logoSize)
                    .graphicsLayer {
                        scaleX = entranceScale * breathingScale
                        scaleY = entranceScale * breathingScale
                        rotationZ = entranceRotation + spin.value
                    }
                    .tvFocusScale()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        spinTarget += 360f
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.application_name),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.easter_egg_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.easter_egg_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

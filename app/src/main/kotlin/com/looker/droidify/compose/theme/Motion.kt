package com.looker.droidify.compose.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/** Material 3's emphasized easing for what arrives: fast out of the gate, settling softly. */
val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Material 3's emphasized easing for what leaves: slow to start, quick to be gone. */
val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** How long an app's version list takes to switch to another source: the piece under the picked source
 *  gliding there, and the new list settling in. */
internal const val SOURCE_SWITCH_MS = 300

/** How long a version list takes to fade into the one replacing it, its pre-release choice changed. */
internal const val VERSION_LIST_SWAP_MS = 250

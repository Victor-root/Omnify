package com.looker.droidify.compose.components

import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.looker.droidify.compose.theme.LocalIsTelevision

/**
 * Android TV only: a text field's keyboard opens when OK is pressed on it, not as soon as the remote
 * lands on it, which threw the keyboard up over the screen whenever the focus merely passed through a
 * field. Goes with [tvKeyboardOptions], which keeps the keyboard shut on focus. OK is used up here, so it
 * never reaches the field as a typed line break. A no-op on touch.
 */
@Composable
fun Modifier.tvKeyboardOnOk(): Modifier {
    if (!LocalIsTelevision.current) return this
    val keyboard = LocalSoftwareKeyboardController.current
    // A dialog is a window of its own, which Android would open the keyboard for on showing it (see
    // keepKeyboardShutOnFocus); the activity's own window is set once, in MainComposeActivity.
    val view = LocalView.current
    SideEffect { (view.parent as? DialogWindowProvider)?.window?.keepKeyboardShutOnFocus() }
    return onPreviewKeyEvent { event ->
        val ok = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
        if (ok && event.type == KeyEventType.KeyUp) keyboard?.show()
        ok
    }
}

/** [options] with the keyboard kept shut when the field gains focus on Android TV: see [tvKeyboardOnOk]. */
@Composable
fun tvKeyboardOptions(options: KeyboardOptions = KeyboardOptions.Default): KeyboardOptions =
    if (LocalIsTelevision.current) options.copy(showKeyboardOnFocus = false) else options

/**
 * Stops Android opening the keyboard by itself when this window shows with a text field focused: on a
 * television, a large screen to it, it does so for any window that resizes around the keyboard, before
 * the app has any say. The keyboard then only ever opens when asked ([tvKeyboardOnOk]). The rest of the
 * window's keyboard behaviour (resizing around it) is kept.
 */
fun Window.keepKeyboardShutOnFocus() {
    val mode = attributes.softInputMode
    val shut = (mode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE.inv()) or
        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
    if (shut != mode) setSoftInputMode(shut)
}

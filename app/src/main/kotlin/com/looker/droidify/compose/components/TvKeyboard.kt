package com.looker.droidify.compose.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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

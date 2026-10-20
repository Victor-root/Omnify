package com.looker.droidify.compose.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.looker.droidify.compose.theme.LocalIsTelevision

/**
 * Android TV only: whether a text field may be typed into. It stays read-only, so no keyboard comes up
 * for it, until OK is pressed on it ([tvTypeOnOk]): otherwise the keyboard was thrown up over the screen
 * whenever the remote's focus merely reached a field, which no keyboard setting on the field or its window
 * prevented on a television. Turning it editable is what brings the keyboard up, at the right moment,
 * so the keyboard itself is never shown or hidden by hand. Off TV the field is always editable.
 */
@Stable
class TvTyping internal constructor(internal val television: Boolean) {
    internal var active by mutableStateOf(false)

    /** The field's `readOnly`. */
    val readOnly: Boolean get() = television && !active

    /** Back to read-only once the keyboard's own action (done, search…) is used. */
    fun stop() {
        active = false
    }
}

@Composable
fun rememberTvTyping(): TvTyping {
    val television = LocalIsTelevision.current
    return remember(television) { TvTyping(television) }
}

/**
 * Makes the text field editable when OK is pressed on it, and read-only again once the focus leaves it:
 * see [TvTyping]. It turns editable when OK is released, not pressed: otherwise the release reaches the
 * field just opened, which some television keyboards take for a validation. OK is used up while the
 * field is read-only, so it never reaches it. A no-op off TV.
 */
fun Modifier.tvTypeOnOk(typing: TvTyping): Modifier {
    if (!typing.television) return this
    return onPreviewKeyEvent { event ->
        val ok = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
        if (typing.active || !ok) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyUp) typing.active = true
        true
    }.onFocusChanged { if (!it.isFocused) typing.active = false }
}

package com.looker.droidify.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * An app's one-line summary, quoted behind an accent bar so it reads as the app's tagline rather than
 * as the description's first paragraph. Shared by the phone hero card and the TV detail header.
 *
 * [centred]: the text is centred, and a blank as wide as the bar balances it on the other side, so the
 * text sits exactly on the card's axis.
 */
@Composable
fun AppTagline(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    centred: Boolean = false,
) {
    Row(
        modifier = modifier
            .then(if (centred) Modifier else Modifier.fillMaxWidth())
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(TaglineBarWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
        )
        Text(
            text = text,
            style = style,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = if (centred) TextAlign.Center else TextAlign.Start,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(horizontal = TaglineGap),
        )
        if (centred) Spacer(Modifier.width(TaglineBarWidth))
    }
}

private val TaglineBarWidth = 3.dp
private val TaglineGap = 12.dp

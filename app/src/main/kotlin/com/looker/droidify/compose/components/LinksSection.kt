package com.looker.droidify.compose.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.looker.droidify.compose.theme.LocalIsTelevision

/** A section header: an optional leading icon and a title — used identically by the F-Droid
 *  catalogue and external app detail screens above their "Links" (and similar) sections. */
@Composable
fun SectionTitle(title: String, @DrawableRes iconRes: Int? = null) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
        Text(text = title, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * One row in a "Links" section: an icon, a title, and a clickable URL subtitle. When [url] is null
 * (a link that genuinely doesn't exist for this app/source, e.g. an external source with no
 * changelog file), [unavailableText] is shown instead in a muted colour and the row isn't clickable
 * — so the section always shows the same rows rather than silently hiding ones with nothing to link
 * to. Shared by the F-Droid catalogue and external app detail screens.
 */
@Composable
fun LinkRow(
    @DrawableRes iconRes: Int,
    title: String,
    url: String?,
    unavailableText: String? = null,
    // TV: lets a caller (e.g. the README's page-scroll) hand D-pad focus straight to this row once it
    // reaches a scroll bound, instead of relying on Compose's own default focus search. Null (the
    // default) leaves this row out of that hand-off. Placed before onClick so a trailing-lambda call
    // site still binds its lambda to onClick, not to this parameter.
    focusRequester: FocusRequester? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // TV only: a soft green fill behind the focused row (a full-width row can't scale without
            // overflowing the screen). No-op on touch.
            .tvFocusFill(RoundedCornerShape(12.dp), debugLabel = "link-row-$title")
            .then(
                when {
                    onClick != null -> Modifier.clickable(onClick = onClick)
                    // TV only, while still resolving/genuinely unavailable: a plain clickable's
                    // focusability disappears along with it once onClick is null, leaving a hole the
                    // D-pad silently skips over. Landable-but-inert instead (the tvFocusFill above
                    // already supplies the highlight, so just .focusable(), not the full tvReadable),
                    // keeps this row a real stop so "down" from the README always has somewhere to go
                    // while a link is still being checked, instead of possibly finding nothing
                    // focusable until whatever loads next.
                    LocalIsTelevision.current -> Modifier.focusable()
                    else -> Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = url ?: unavailableText.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (url != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

package com.looker.droidify.compose.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.looker.droidify.data.model.Fingerprint
import com.looker.droidify.data.model.formattedString

/**
 * A bordered card presenting one hex fingerprint (a repo's own signing fingerprint, an installed app's
 * certificate, …) as its own prominent block: this kind of value is what the user actually came here to
 * check or compare, not routine metadata worth burying in a smaller line of text. [onClick] is optional:
 * pass one (e.g. to copy [content] to the clipboard) to make the whole card actionable, or leave it null
 * for a purely informational card (the repo screen's own original use).
 */
@Composable
fun FingerprintCard(
    title: String,
    content: AnnotatedString,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.large
    // See the doc comment on premiumCardBorder's HeroCard usage: the border must live on this
    // outer Box, not inside Surface's own modifier, or its own background paints over it.
    Box(modifier = modifier.fillMaxWidth().then(premiumCardBorder(shape))) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                // TV only, no-op on touch (and harmless when onClick is null: with nothing below to
                // ever take focus, these never fire): visible focus fill + scroll-into-view, matching
                // how other shared clickable components (e.g. PackageItem) handle TV focus themselves
                // rather than leaving every caller to repeat it.
                .tvFocusFill(shape)
                .tvBringIntoViewOnFocus()
                .let { if (onClick != null) it.clickable(onClick = onClick) else it },
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = content,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** [fingerprint] as spaced, uppercase hex pairs in monospace: the same look [FingerprintCard] already
 *  used for a repo's own fingerprint (see RepoDetailScreen's own formatFingerprint), reused here for any
 *  other hex fingerprint (an installed app's signing certificate, …) that has no repo-specific "unsigned"
 *  fallback to show instead. */
fun fingerprintContent(fingerprint: Fingerprint): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
        append(fingerprint.formattedString())
    }
}

package com.looker.droidify.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 * An app's one-line summary, centred in an accent-tinted box so it no longer reads as the
 * description's first paragraph. Shows the translated summary while [translation] holds one. Shared by
 * the phone and TV detail screens.
 */
@Composable
fun AppSummaryCard(
    summary: String,
    translation: DescriptionTranslation,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val shownSummary = (translation as? DescriptionTranslation.Translated)
        ?.summary?.takeIf { it.isNotBlank() } ?: summary
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(14.dp)
    Text(
        text = shownSummary,
        style = textStyle,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.12f), shape)
            .border(1.dp, accent.copy(alpha = 0.45f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

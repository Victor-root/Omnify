package com.looker.droidify.compose.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.looker.droidify.R

/**
 * UI state of a "Translate" toggle. Shared by the catalogue app detail (summary + description) and the
 * external app detail (the project README, carried in [Translated.description] with an empty summary).
 */
sealed interface DescriptionTranslation {
    /** Showing the original text. */
    data object Original : DescriptionTranslation

    /** Translation in progress (covers the first-use ML Kit model download too). */
    data object Loading : DescriptionTranslation

    /** Showing the translated summary + description, plus the what's-new text when there is one. */
    data class Translated(
        val summary: String,
        val description: String,
        val whatsNew: String = "",
    ) : DescriptionTranslation

    /** The translation couldn't be produced (offline, bad config, unsupported language…). */
    data object Failed : DescriptionTranslation
}

/**
 * The header "Translate" button: a plain icon to start, a wavy ring around the icon while translating,
 * and a tap-to-revert icon once translated. Lives in a top bar's `actions`.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TranslateAction(
    translation: DescriptionTranslation,
    onTranslate: () -> Unit,
    onShowOriginal: () -> Unit,
) {
    when (translation) {
        DescriptionTranslation.Loading -> Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularWavyProgressIndicator(
                modifier = Modifier.size(36.dp),
                color = LocalContentColor.current,
            )
            Icon(
                imageVector = Icons.Filled.Translate,
                contentDescription = stringResource(R.string.translating),
                modifier = Modifier.size(18.dp),
            )
        }

        is DescriptionTranslation.Translated -> IconButton(onClick = onShowOriginal) {
            Icon(
                imageVector = Icons.Filled.Translate,
                contentDescription = stringResource(R.string.show_original),
            )
        }

        // Original or Failed: tapping (re)translates.
        else -> IconButton(onClick = onTranslate) {
            Icon(
                imageVector = Icons.Filled.Translate,
                contentDescription = stringResource(R.string.translate),
            )
        }
    }
}

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
import com.looker.droidify.compose.theme.LocalIsTelevision

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
    val loading = translation == DescriptionTranslation.Loading
    val translated = translation is DescriptionTranslation.Translated
    // Always an IconButton, in every state: tapping translate flips to Loading, and if that swapped the
    // button for a plain (non-focusable) spinner the D-pad focus would jump away to the back arrow. By
    // keeping the same focusable node and only changing its content, focus stays put while translating.
    val description = when {
        loading -> stringResource(R.string.translating)
        translated -> stringResource(R.string.show_original)
        else -> stringResource(R.string.translate)
    }
    IconButton(
        onClick = {
            when {
                loading -> Unit
                translated -> onShowOriginal()
                else -> onTranslate()
            }
        },
        // TV: a square button so the focus halo is a clean circle, and the icon scales up on focus.
        modifier = (if (LocalIsTelevision.current) Modifier.size(48.dp) else Modifier).tvFocusScale(),
    ) {
        if (loading) {
            Box(contentAlignment = Alignment.Center) {
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(30.dp),
                    color = LocalContentColor.current,
                )
                Icon(
                    imageVector = Icons.Filled.Translate,
                    contentDescription = description,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            Icon(
                imageVector = Icons.Filled.Translate,
                contentDescription = description,
            )
        }
    }
}

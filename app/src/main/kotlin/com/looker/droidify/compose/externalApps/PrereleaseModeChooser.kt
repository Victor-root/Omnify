package com.looker.droidify.compose.externalApps

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.looker.droidify.R
import com.looker.droidify.compose.components.SelectionDialog
import com.looker.droidify.compose.components.tvFocusScale
import com.looker.droidify.external.PrereleaseMode
import com.looker.droidify.external.Release

/**
 * The version list's own choice of what to do with the repository's pre-releases: a button in its header
 * naming the current choice, opening the list of them. That they may be unstable is said once, in that
 * list, while they are still hidden, and nowhere else but the badge each one wears.
 */
@Composable
internal fun PrereleaseModeChooser(
    mode: PrereleaseMode,
    onModeChange: (PrereleaseMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { choosing = true }, modifier = modifier.tvFocusScale()) {
        Text(stringResource(R.string.prereleases_button_FORMAT, prereleaseModeName(mode)))
    }
    if (choosing) {
        SelectionDialog(
            title = stringResource(R.string.prereleases_title),
            selectedValue = mode,
            values = PrereleaseMode.entries,
            valueToString = { prereleaseModeName(it) },
            valueDescription = { prereleaseModeDescription(it) },
            message = if (mode == PrereleaseMode.HIDDEN) stringResource(R.string.prereleases_warning) else null,
            onValueSelected = {
                onModeChange(it)
                choosing = false
            },
            onDismiss = { choosing = false },
        )
    }
}

/** Whether the version list offers [PrereleaseModeChooser]: not for a repository publishing nothing but
 *  pre-releases, where there is no choice to make. Under [PrereleaseMode.HIDDEN] such a repository is the
 *  only way pre-releases reach the list; under any other mode, the list holding only pre-releases says
 *  nothing about the repository, so the choice stays there to be undone. */
internal fun offersPrereleaseChoice(mode: PrereleaseMode, releases: List<Release>): Boolean =
    mode != PrereleaseMode.HIDDEN || releases.any { !it.isPrerelease }

/** Whether [tag] is a pre-release among [releases]: what puts the badge on the app's card. */
internal fun List<Release>.isPrerelease(tag: String?): Boolean =
    tag != null && any { it.tag == tag && it.isPrerelease }

@Composable
private fun prereleaseModeName(mode: PrereleaseMode): String = stringResource(
    when (mode) {
        PrereleaseMode.HIDDEN -> R.string.prereleases_hidden
        PrereleaseMode.SHOWN -> R.string.prereleases_shown
        PrereleaseMode.BOTH -> R.string.prereleases_both
        PrereleaseMode.ONLY -> R.string.prereleases_only
    },
)

@Composable
private fun prereleaseModeDescription(mode: PrereleaseMode): String = stringResource(
    when (mode) {
        PrereleaseMode.HIDDEN -> R.string.prereleases_hidden_DESC
        PrereleaseMode.SHOWN -> R.string.prereleases_shown_DESC
        PrereleaseMode.BOTH -> R.string.prereleases_both_DESC
        PrereleaseMode.ONLY -> R.string.prereleases_only_DESC
    },
)

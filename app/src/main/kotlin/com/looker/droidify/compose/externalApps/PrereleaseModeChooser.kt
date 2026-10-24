package com.looker.droidify.compose.externalApps

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.looker.droidify.R
import com.looker.droidify.compose.components.SelectionDialog
import com.looker.droidify.compose.components.tvFocusScale
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.PrereleaseMode
import com.looker.droidify.external.Release
import com.looker.droidify.external.apkFileName
import com.looker.droidify.external.apkFileSize
import com.looker.droidify.external.releaseVersionLabel

/**
 * The version list's own choice of what to do with the repository's pre-releases: a button in its header
 * naming the current choice, opening the list of them. That they may be unstable is said once, in that
 * list, while they are still hidden, and nowhere else but the badge each one wears.
 */
@Composable
internal fun PrereleaseModeChooser(
    mode: PrereleaseMode,
    onModeChange: (PrereleaseMode) -> Unit,
    // The version list's own title style, which this sits beside and reads as part of.
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    val name = prereleaseModeName(mode)
    val label = stringResource(R.string.prereleases_button_FORMAT, name)
    val accent = MaterialTheme.colorScheme.primary
    // Worded like the title beside it, the choice alone in the accent once it lets pre-releases in.
    val text = remember(label, name, mode, accent) {
        buildAnnotatedString {
            append(label)
            val start = label.lastIndexOf(name)
            if (mode != PrereleaseMode.HIDDEN && start >= 0) {
                addStyle(SpanStyle(color = accent), start, start + name.length)
            }
        }
    }
    TextButton(
        onClick = { choosing = true },
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = modifier.tvFocusScale(),
    ) {
        Text(text = text, style = textStyle)
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

/** The version the app's card shows, its APK size, and whether it is a pre-release (the badge). */
internal data class CardVersion(val label: String?, val size: Long?, val isPrerelease: Boolean)

/**
 * What [CardVersion] says for this app, [history] being its version list: the release it offers, except
 * while pre-releases are shown without being offered ([PrereleaseMode.SHOWN]), when a pre-release newer
 * than it is what the card shows, so that the card says what the list below it does.
 */
internal fun ExternalApp.cardVersion(history: List<Release>?): CardVersion {
    val newestShown = history?.firstOrNull()?.takeIf { prereleases == PrereleaseMode.SHOWN && it.isPrerelease }
    if (newestShown != null) {
        return CardVersion(
            label = releaseVersionLabel(newestShown.apkFileName(filter = apkFilter), newestShown.tag).ifEmpty { null },
            size = newestShown.apkFileSize(filter = apkFilter),
            isPrerelease = true,
        )
    }
    return CardVersion(
        label = releaseVersionLabel(latestApkName, latestTag).ifEmpty { null },
        size = latestApkSize,
        isPrerelease = latestTag != null && history.orEmpty().any { it.tag == latestTag && it.isPrerelease },
    )
}

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

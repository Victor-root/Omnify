package com.looker.droidify.compose.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.components.TvOverscan
import com.looker.droidify.compose.components.tvFocusFill
import com.looker.droidify.compose.components.tvFocusScale
import com.looker.droidify.compose.components.verticalScrollbar
import com.looker.droidify.compose.repoList.AppLauncherIcon
import com.looker.droidify.compose.settings.components.SettingHeader
import com.looker.droidify.compose.welcome.WelcomeItem
import com.looker.droidify.compose.welcome.WelcomeKind
import com.looker.droidify.compose.welcome.WelcomeSourceDescription
import com.looker.droidify.compose.welcome.WelcomeSourceIcon
import com.looker.droidify.compose.welcome.WelcomeViewModel
import com.looker.droidify.compose.welcome.rememberCanInstallApps
import com.looker.droidify.utility.common.openUnknownAppSourcesSettings
import kotlinx.coroutines.delay

/**
 * The Android TV welcome page of a fresh install (see [com.looker.droidify.compose.welcome.WelcomeScreen]
 * for what it is for), laid out for a remote: everything but the sources stays on the left (the title,
 * the two actions and, while it is still to be given, the permission to install apps), so "Continue"
 * never has to be scrolled to and the sources get the whole height of the right. Each source is a single
 * focus stop that OK ticks or clears, and the apps made for television follow the repositories. Focus
 * starts on "Continue", so accepting the usual choices takes one press.
 * It is the same [WelcomeViewModel], so what it does is exactly what the phone page does. Never
 * composed off TV.
 */
@Composable
fun TvWelcomeScreen(
    viewModel: WelcomeViewModel,
    onDone: () -> Unit,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val finishing by viewModel.finishing.collectAsStateWithLifecycle()
    val canInstallApps = rememberCanInstallApps()
    val context = LocalContext.current
    val allChecked = items.isNotEmpty() && items.all { it.checked }

    // Android TV must always land the D-pad focus somewhere on entry, or a remote press with nothing
    // focused times out input dispatch and kills the app. Retried briefly because the page isn't laid
    // out on the very first frame.
    val continueFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { continueFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TvAccentBackground()
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = TvOverscan + 8.dp,
                    end = TvOverscan + 8.dp,
                    top = TvOverscan,
                    bottom = TvOverscan,
                ),
            horizontalArrangement = spacedBy(48.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(0.4f)
                    .fillMaxHeight()
                    .focusGroup(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                AppLauncherIcon(modifier = Modifier.size(88.dp))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.welcome_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.welcome_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.finish(onDone) },
                    enabled = !finishing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .tvFocusScale(1.05f)
                        .focusRequester(continueFocus),
                ) {
                    Text(
                        stringResource(
                            if (items.any { it.checked }) {
                                R.string.welcome_continue
                            } else {
                                R.string.welcome_continue_without_sources
                            },
                        ),
                    )
                }
                OutlinedButton(
                    onClick = { viewModel.setAll(!allChecked) },
                    enabled = !finishing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .tvFocusScale(1.05f),
                ) {
                    Text(
                        stringResource(
                            if (allChecked) R.string.welcome_deselect_all else R.string.welcome_select_all,
                        ),
                    )
                }
                if (!canInstallApps) {
                    TvInstallPermissionRow(onAllow = { context.openUnknownAppSourcesSettings() })
                }
            }
            val listState = rememberLazyListState()
            // The apps made for television follow the repositories under a heading of their own.
            val firstTvApp = items.indexOfFirst { it.kind is WelcomeKind.TvApp }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxHeight()
                    .verticalScrollbar(listState)
                    .focusGroup(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    Column {
                        if (index == firstTvApp) SettingHeader(stringResource(R.string.discover_tv_apps))
                        TvWelcomeSourceRow(
                            item = item,
                            enabled = !finishing,
                            onToggle = { viewModel.toggle(item.id) },
                        )
                    }
                }
            }
        }
    }
}

/** One source. The whole row, tick included, is a single focus stop that flips the tick, the simplest
 *  interaction on a remote, as on the repositories screen's own rows. */
@Composable
private fun TvWelcomeSourceRow(
    item: WelcomeItem,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusFill(RoundedCornerShape(16.dp))
            .toggleable(
                value = item.checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        WelcomeSourceIcon(item = item, size = 56.dp)
        Spacer(Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            WelcomeSourceDescription(item = item, maxLines = 2)
        }
        Spacer(Modifier.size(12.dp))
        // Display-only: the row is the focus stop, so the tick is not a second one.
        Checkbox(checked = item.checked, onCheckedChange = null)
    }
}

/** The permission to install apps, as one more focus stop under the two buttons. */
@Composable
private fun TvInstallPermissionRow(onAllow: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusFill(RoundedCornerShape(16.dp))
            .clickable(onClick = onAllow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_apk_install),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.allow_unknown_sources),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.welcome_install_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.allow),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

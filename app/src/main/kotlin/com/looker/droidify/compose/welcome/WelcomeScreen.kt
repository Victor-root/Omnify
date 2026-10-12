package com.looker.droidify.compose.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.components.FloatingAppCardsBackground
import com.looker.droidify.compose.components.premiumCardBorder
import com.looker.droidify.compose.repoList.AppLauncherIcon
import com.looker.droidify.compose.repoList.RepoIcon
import com.looker.droidify.compose.repoList.defaultRepoIcon
import com.looker.droidify.compose.repoList.defaultRepoIconRes
import com.looker.droidify.compose.theme.LocalStatusBarContent
import com.looker.droidify.utility.common.canRequestPackageInstalls
import com.looker.droidify.utility.common.openUnknownAppSourcesSettings

private val ContentMaxWidth = 560.dp

/**
 * The page a fresh install opens on: it asks where the apps should come from before anything is
 * downloaded, instead of switching sources on by itself and starting to synchronise at once. The
 * recommended sources are listed with the usual ones ticked, one button ticks or clears them all, and
 * the permission to install apps, which used to be a dialog on its own, is asked right here.
 * Continuing, with or without any source, switches the ticked ones on and opens the home, and that is
 * when the first synchronisation starts. The Android TV version is [com.looker.droidify.compose.tv.TvWelcomeScreen].
 */
@Composable
fun WelcomeScreen(
    viewModel: WelcomeViewModel,
    onDone: () -> Unit,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val finishing by viewModel.finishing.collectAsStateWithLifecycle()
    val canInstallApps = rememberCanInstallApps()
    val context = LocalContext.current
    val allChecked = items.isNotEmpty() && items.all { it.checked }

    // No accent bar sits under the status bar here, so its icons must contrast with the page instead.
    val statusBarContent = LocalStatusBarContent.current
    DisposableEffect(statusBarContent) {
        val owner = Any()
        statusBarContent.publish(owner, showsContent = true)
        onDispose { statusBarContent.release(owner) }
    }

    Scaffold(
        bottomBar = {
            WelcomeBottomBar(
                anyChecked = items.any { it.checked },
                enabled = !finishing,
                onContinue = { viewModel.finish(onDone) },
            )
        },
    ) { contentPadding ->
        FloatingAppCardsBackground()
        val direction = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(direction) + 16.dp,
                top = contentPadding.calculateTopPadding() + 24.dp,
                end = contentPadding.calculateEndPadding(direction) + 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            item(key = "header") {
                WelcomeHeader(modifier = Modifier.widthIn(max = ContentMaxWidth))
            }
            item(key = "sources-header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxWidth()
                        .padding(start = 8.dp, top = 24.dp),
                ) {
                    Text(
                        text = stringResource(R.string.welcome_sources_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.setAll(!allChecked) }, enabled = !finishing) {
                        Text(
                            stringResource(
                                if (allChecked) R.string.welcome_deselect_all else R.string.welcome_select_all,
                            ),
                        )
                    }
                }
            }
            item(key = "sources") {
                val shape = MaterialTheme.shapes.large
                // The border lives on the outer Box: the Surface's own background would paint over it
                // (see premiumCardBorder's use in HeroCard).
                Box(
                    modifier = Modifier
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxWidth()
                        .then(premiumCardBorder(shape)),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = shape,
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column {
                            items.forEachIndexed { index, item ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 80.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                }
                                WelcomeSourceRow(
                                    item = item,
                                    enabled = !finishing,
                                    onToggle = { viewModel.toggle(item.id) },
                                )
                            }
                        }
                    }
                }
            }
            if (!canInstallApps) {
                item(key = "install") {
                    InstallPermissionCard(
                        onAllow = { context.openUnknownAppSourcesSettings() },
                        modifier = Modifier
                            .widthIn(max = ContentMaxWidth)
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeHeader(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        AppLauncherIcon(modifier = Modifier.size(88.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.welcome_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** One recommended source: its logo, what it is, and a tick. The whole row is the control. */
@Composable
private fun WelcomeSourceRow(
    item: WelcomeItem,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = item.checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        WelcomeSourceIcon(item = item, modifier = Modifier.size(48.dp))
        Spacer(Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(item.description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(8.dp))
        // Display-only: the row above is what toggles it, so the tick is not a second control.
        Checkbox(checked = item.checked, onCheckedChange = null)
    }
}

/** The permission to install apps, which used to be asked in a dialog of its own on the first start. */
@Composable
private fun InstallPermissionCard(onAllow: () -> Unit, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.large
    Box(modifier = modifier.then(premiumCardBorder(shape))) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_apk_install),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.allow_unknown_sources),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.allow_unknown_sources_DESC),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAllow, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.allow))
                }
            }
        }
    }
}

/** The one way out of the page, pinned under the list so it never has to be scrolled to. */
@Composable
private fun WelcomeBottomBar(
    anyChecked: Boolean,
    enabled: Boolean,
    onContinue: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Button(
                onClick = onContinue,
                enabled = enabled,
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(
                    stringResource(
                        if (anyChecked) R.string.welcome_continue else R.string.welcome_continue_without_sources,
                    ),
                )
            }
        }
    }
}

/** A recommended source's logo: the app's own icon for Omnify, the repository's for the others. */
@Composable
internal fun WelcomeSourceIcon(item: WelcomeItem, modifier: Modifier = Modifier) {
    val repo = item.repo
    if (repo == null) {
        AppLauncherIcon(modifier = modifier)
    } else {
        RepoIcon(
            iconUrl = repo.icon?.path,
            fallbackUrl = defaultRepoIcon(repo.address),
            name = repo.name,
            modifier = modifier,
            fallbackRes = defaultRepoIconRes(repo.address),
            version = repo.versionInfo?.timestamp,
        )
    }
}

/** Whether Omnify may install apps right now, read again every time the app comes back to the front:
 *  the permission is granted on a system page, which this page is left for and returned from. */
@Composable
internal fun rememberCanInstallApps(): Boolean {
    val context = LocalContext.current
    var canInstall by remember { mutableStateOf(context.canRequestPackageInstalls()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canInstall = context.canRequestPackageInstalls() }
    return canInstall
}

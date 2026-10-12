package com.looker.droidify.compose.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.components.FloatingAppCardsBackground
import com.looker.droidify.compose.components.premiumCardBorder
import com.looker.droidify.compose.components.verticalScrollbar
import com.looker.droidify.compose.externalApps.ExternalAppIcon
import com.looker.droidify.compose.repoList.AppLauncherIcon
import com.looker.droidify.compose.repoList.RepoIcon
import com.looker.droidify.compose.repoList.defaultRepoIcon
import com.looker.droidify.compose.repoList.defaultRepoIconRes
import com.looker.droidify.compose.theme.LocalStatusBarContent
import com.looker.droidify.utility.common.canRequestPackageInstalls
import com.looker.droidify.utility.common.openUnknownAppSourcesSettings
import com.looker.droidify.utility.text.toAnnotatedString

private val ContentMaxWidth = 560.dp

/** How much of the header the screen's height leaves room for. */
private enum class HeaderSize { Large, Medium, Small }

private fun headerSizeFor(height: Dp): HeaderSize = when {
    height >= 780.dp -> HeaderSize.Large
    height >= 560.dp -> HeaderSize.Medium
    else -> HeaderSize.Small
}

/**
 * The page a fresh install opens on: it asks where the apps should come from before anything is
 * downloaded, instead of switching sources on by itself and starting to synchronise at once.
 *
 * Everything fits one screen that never scrolls, whatever the screen: the sources sit in a card of
 * their own that scrolls inside itself, the header gives up room as the screen gets shorter, and a
 * wide screen lays the page out in two columns instead of one. The permission to install apps, which
 * used to be a dialog on its own, is asked right here. The round button at the bottom switches the
 * ticked sources on, with or without any, and opens the home, which is when the first synchronisation
 * starts. The Android TV version is [com.looker.droidify.compose.tv.TvWelcomeScreen].
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

    val sourcesCard = @Composable { modifier: Modifier ->
        WelcomeSourcesCard(
            items = items,
            allChecked = allChecked,
            enabled = !finishing,
            onToggle = viewModel::toggle,
            onSetAll = { viewModel.setAll(!allChecked) },
            modifier = modifier,
        )
    }
    val actions = @Composable { modifier: Modifier ->
        WelcomeActions(
            canInstallApps = canInstallApps,
            anyChecked = items.any { it.checked },
            onAllow = { context.openUnknownAppSourcesSettings() },
            onNext = { viewModel.finish(onDone) },
            modifier = modifier,
        )
    }

    Scaffold { contentPadding ->
        FloatingAppCardsBackground()
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            val headerSize = headerSizeFor(maxHeight)
            if (maxWidth > maxHeight) {
                // Wide: the title and the way forward on the left, the sources taking the whole height
                // on the right.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                ) {
                    Column(
                        verticalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.weight(0.4f).fillMaxHeight(),
                    ) {
                        WelcomeHeader(size = headerSize)
                        actions(Modifier.fillMaxWidth())
                    }
                    sourcesCard(Modifier.weight(0.6f).fillMaxHeight())
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    WelcomeHeader(size = headerSize)
                    Spacer(Modifier.height(16.dp))
                    sourcesCard(Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    actions(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun WelcomeHeader(size: HeaderSize, modifier: Modifier = Modifier) {
    if (size == HeaderSize.Small) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            modifier = modifier.fillMaxWidth(),
        ) {
            AppLauncherIcon(modifier = Modifier.size(40.dp))
            Text(
                text = stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        return
    }
    val large = size == HeaderSize.Large
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        AppLauncherIcon(modifier = Modifier.size(if (large) 72.dp else 56.dp))
        Spacer(Modifier.height(if (large) 16.dp else 8.dp))
        Text(
            text = stringResource(R.string.welcome_title),
            style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.welcome_subtitle),
            style = if (large) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The sources, in a card that fills whatever room the page leaves and scrolls inside itself, with its
 * own scroll indicator. The title and the tick-all button stay put above the list.
 */
@Composable
private fun WelcomeSourcesCard(
    items: List<WelcomeItem>,
    allChecked: Boolean,
    enabled: Boolean,
    onToggle: (String) -> Unit,
    onSetAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.large
    // The border lives on the outer Box: the Surface's own background would paint over it (see
    // premiumCardBorder's use in HeroCard).
    Box(modifier = modifier.then(premiumCardBorder(shape))) {
        Surface(modifier = Modifier.fillMaxSize(), shape = shape, color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.welcome_sources_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onSetAll, enabled = enabled) {
                        Text(
                            stringResource(
                                if (allChecked) R.string.welcome_deselect_all else R.string.welcome_select_all,
                            ),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScrollbar(listState),
                ) {
                    itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 74.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        WelcomeSourceRow(item = item, enabled = enabled, onToggle = { onToggle(item.id) })
                    }
                }
            }
        }
    }
}

/** One source: its logo, what it is, and a tick. The whole row is the control. */
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        WelcomeSourceIcon(item = item, size = 44.dp)
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            WelcomeSourceDescription(item = item, maxLines = 2)
        }
        Spacer(Modifier.size(8.dp))
        // Display-only: the row above is what toggles it, so the tick is not a second control.
        Checkbox(checked = item.checked, onCheckedChange = null)
    }
}

/**
 * What sits under the sources: the permission to install apps while it is still to be given, and the
 * round button that goes on to the home. A chevron rather than a label, so the way forward reads the
 * same on every screen and in every language, with the words kept for screen readers.
 */
@Composable
private fun WelcomeActions(
    canInstallApps: Boolean,
    anyChecked: Boolean,
    onAllow: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier,
    ) {
        if (canInstallApps) {
            Spacer(Modifier.weight(1f))
        } else {
            InstallPermissionCard(onAllow = onAllow, modifier = Modifier.weight(1f))
        }
        FloatingActionButton(onClick = onNext) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(
                    if (anyChecked) R.string.welcome_continue else R.string.welcome_continue_without_sources,
                ),
            )
        }
    }
}

/** The permission to install apps, which used to be asked in a dialog of its own on the first start. */
@Composable
private fun InstallPermissionCard(onAllow: () -> Unit, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.large
    Box(modifier = modifier.then(premiumCardBorder(shape))) {
        Surface(modifier = Modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surface) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_apk_install),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.allow_unknown_sources),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.welcome_install_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onAllow) { Text(stringResource(R.string.allow)) }
            }
        }
    }
}

/** A source's logo: the app's own icon for Omnify's, the repository's or the app's for the others. */
@Composable
internal fun WelcomeSourceIcon(item: WelcomeItem, size: Dp, modifier: Modifier = Modifier) {
    when (val kind = item.kind) {
        WelcomeKind.OmnifySource, WelcomeKind.OmnifyAccount ->
            AppLauncherIcon(modifier = modifier.size(size))

        is WelcomeKind.FdroidRepository -> RepoIcon(
            iconUrl = kind.repo.icon?.path,
            fallbackUrl = defaultRepoIcon(kind.repo.address),
            name = kind.repo.name,
            modifier = modifier.size(size),
            fallbackRes = defaultRepoIconRes(kind.repo.address),
            version = kind.repo.versionInfo?.timestamp,
        )

        is WelcomeKind.TvApp -> ExternalAppIcon(
            app = kind.app,
            isInstalled = false,
            size = size,
            modifier = modifier,
        )
    }
}

/** What a source is: the line written for this page when there is one, else the description the
 *  repository gives itself, else where the app comes from. */
@Composable
internal fun WelcomeSourceDescription(
    item: WelcomeItem,
    maxLines: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
) {
    val text = when (val kind = item.kind) {
        WelcomeKind.OmnifySource -> AnnotatedString(stringResource(R.string.welcome_source_omnify))
        WelcomeKind.OmnifyAccount ->
            AnnotatedString(stringResource(R.string.external_account_omnify_description))

        is WelcomeKind.FdroidRepository -> kind.description?.let { AnnotatedString(stringResource(it)) }
            ?: remember(kind.repo.description) { kind.repo.description.toAnnotatedString { } }

        is WelcomeKind.TvApp -> AnnotatedString("${kind.app.sourceLabel} · ${kind.app.path}")
    }
    Text(
        text = text,
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
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

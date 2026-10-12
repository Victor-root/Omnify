package com.looker.droidify.compose.welcome

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.components.BackButton
import com.looker.droidify.compose.components.FloatingAppCardsBackground
import com.looker.droidify.compose.components.PageIndicator
import com.looker.droidify.compose.components.premiumCardBorder
import com.looker.droidify.compose.components.verticalScrollbar
import com.looker.droidify.compose.externalApps.ExternalAppIcon
import com.looker.droidify.compose.repoList.AppLauncherIcon
import com.looker.droidify.compose.repoList.RepoIcon
import com.looker.droidify.compose.repoList.defaultRepoIcon
import com.looker.droidify.compose.repoList.defaultRepoIconRes
import com.looker.droidify.compose.settings.InstallerTypeSetting
import com.looker.droidify.compose.theme.LocalStatusBarContent
import com.looker.droidify.datastore.model.InstallerType
import com.looker.droidify.utility.text.toAnnotatedString
import kotlinx.coroutines.launch

private val ContentMaxWidth = 560.dp

/** The bar under the pages: the way back, where the person is, the way forward. */
private val NavigationBarHeight = 80.dp

private const val PAGE_COUNT = 2

/** How much of the header the screen's height leaves room for. */
private enum class HeaderSize { Large, Medium, Small }

private fun headerSizeFor(height: Dp): HeaderSize = when {
    height >= 780.dp -> HeaderSize.Large
    height >= 560.dp -> HeaderSize.Medium
    else -> HeaderSize.Small
}

/**
 * What a fresh install opens on, in two pages with a pager's dots under them.
 *
 * The first welcomes the person and asks, one button each, for what Omnify needs from the device:
 * notifications, the install of apps, and the installer to use. Nothing is asked out of the blue at
 * start-up any more. The second asks where the apps should come from, before anything is downloaded,
 * instead of switching sources on by itself and starting to synchronise at once.
 *
 * Neither page scrolls as a page, whatever the screen: the sources sit in a card of their own that
 * scrolls inside itself, the header gives up room as the screen gets shorter, and a wide screen lays a
 * page out in two columns instead of one. The round button goes to the next page, then switches the
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
    val installerType by viewModel.installerType.collectAsStateWithLifecycle()
    val steps = rememberWelcomeSteps()
    val allChecked = items.isNotEmpty() && items.all { it.checked }
    val pagerState = rememberPagerState { PAGE_COUNT }
    val scope = rememberCoroutineScope()
    val currentPage = pagerState.currentPage

    fun goToPage(page: Int) {
        scope.launch { pagerState.animateScrollToPage(page) }
    }
    BackHandler(enabled = currentPage > 0) { goToPage(currentPage - 1) }

    // No accent bar sits under the status bar here, so its icons must contrast with the page instead.
    val statusBarContent = LocalStatusBarContent.current
    DisposableEffect(statusBarContent) {
        val owner = Any()
        statusBarContent.publish(owner, showsContent = true)
        onDispose { statusBarContent.release(owner) }
    }

    Scaffold { contentPadding ->
        FloatingAppCardsBackground()
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            val wide = maxWidth > maxHeight
            val headerSize = headerSizeFor(maxHeight - NavigationBarHeight)
            Column(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { page ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        if (page == 0) {
                            WelcomeSetupPage(
                                wide = wide,
                                headerSize = headerSize,
                                steps = steps,
                                installerType = installerType,
                                onInstallerSelected = viewModel::setInstaller,
                            )
                        } else {
                            WelcomeSourcesPage(
                                wide = wide,
                                headerSize = headerSize,
                                items = items,
                                allChecked = allChecked,
                                enabled = !finishing,
                                onToggle = viewModel::toggle,
                                onSetAll = { viewModel.setAll(!allChecked) },
                            )
                        }
                    }
                }
                WelcomeNavigationBar(
                    currentPage = currentPage,
                    anyChecked = items.any { it.checked },
                    onBack = { goToPage(currentPage - 1) },
                    onNext = {
                        if (currentPage == PAGE_COUNT - 1) viewModel.finish(onDone) else goToPage(currentPage + 1)
                    },
                )
            }
        }
    }
}

/** The first page: the welcome, and what Omnify needs to be allowed. */
@Composable
private fun WelcomeSetupPage(
    wide: Boolean,
    headerSize: HeaderSize,
    steps: List<WelcomeStep>,
    installerType: InstallerType,
    onInstallerSelected: (InstallerType) -> Unit,
) {
    if (wide) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().padding(16.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(0.4f)) {
                WelcomeHeader(size = headerSize)
            }
            WelcomeSetupCard(
                steps = steps,
                installerType = installerType,
                onInstallerSelected = onInstallerSelected,
                modifier = Modifier.weight(0.6f),
            )
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize().padding(16.dp),
        ) {
            WelcomeHeader(size = headerSize)
            Spacer(Modifier.height(16.dp))
            WelcomeSetupCard(
                steps = steps,
                installerType = installerType,
                onInstallerSelected = onInstallerSelected,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** The second page: where the apps should come from. */
@Composable
private fun WelcomeSourcesPage(
    wide: Boolean,
    headerSize: HeaderSize,
    items: List<WelcomeItem>,
    allChecked: Boolean,
    enabled: Boolean,
    onToggle: (String) -> Unit,
    onSetAll: () -> Unit,
) {
    if (wide) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().padding(16.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(0.4f)) {
                WelcomeSourcesHeading(compact = headerSize == HeaderSize.Small)
            }
            WelcomeSourcesCard(
                items = items,
                allChecked = allChecked,
                enabled = enabled,
                onToggle = onToggle,
                onSetAll = onSetAll,
                modifier = Modifier.weight(0.6f).fillMaxSize(),
            )
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize().padding(16.dp),
        ) {
            WelcomeSourcesHeading(compact = headerSize == HeaderSize.Small)
            Spacer(Modifier.height(16.dp))
            WelcomeSourcesCard(
                items = items,
                allChecked = allChecked,
                enabled = enabled,
                onToggle = onToggle,
                onSetAll = onSetAll,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
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

/** What the second page is asking, kept to its title when the screen is too short for the sentence. */
@Composable
private fun WelcomeSourcesHeading(compact: Boolean, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.welcome_sources_page_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (!compact) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.welcome_sources_page_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** What Omnify needs from the device, in a card: each permission with its button, then the installer. */
@Composable
private fun WelcomeSetupCard(
    steps: List<WelcomeStep>,
    installerType: InstallerType,
    onInstallerSelected: (InstallerType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.large
    // The border lives on the outer Box: the Surface's own background would paint over it (see
    // premiumCardBorder's use in HeroCard).
    Box(modifier = modifier.then(premiumCardBorder(shape))) {
        Surface(modifier = Modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surface) {
            // Scrolls only if a very small screen or a very large font leaves it no other way to fit.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                steps.forEach { step ->
                    WelcomeStepRow(step)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                InstallerTypeSetting(
                    icon = painterResource(R.drawable.ic_tabler_package),
                    selectedInstaller = installerType,
                    onInstallerSelected = onInstallerSelected,
                )
            }
        }
    }
}

/** One permission: what it is for, and a button to give it, or a tick once it is given. */
@Composable
private fun WelcomeStepRow(step: WelcomeStep) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Icon(
            painter = painterResource(step.icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(step.title), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(step.description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (step.done) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = stringResource(R.string.welcome_allowed),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            FilledTonalButton(onClick = step.onClick) { Text(stringResource(R.string.allow)) }
        }
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
 * Under the pages: the way back once there is one, the dots, and the round button forward. A chevron
 * rather than a label, so the way forward reads the same on every screen and in every language, with
 * the words kept for screen readers; the last page turns it into a tick, since that one finishes.
 */
@Composable
private fun WelcomeNavigationBar(
    currentPage: Int,
    anyChecked: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val last = currentPage == PAGE_COUNT - 1
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().height(NavigationBarHeight).padding(horizontal = 16.dp),
    ) {
        if (currentPage > 0) {
            BackButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart))
        }
        PageIndicator(pageCount = PAGE_COUNT, currentPage = currentPage)
        FloatingActionButton(onClick = onNext, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(
                imageVector = if (last) Icons.Filled.Check else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(
                    when {
                        !last -> R.string.welcome_next
                        anyChecked -> R.string.welcome_continue
                        else -> R.string.welcome_continue_without_sources
                    },
                ),
            )
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

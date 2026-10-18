package com.looker.droidify.compose.welcome

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import com.looker.droidify.R
import com.looker.droidify.compose.components.MutedButton
import com.looker.droidify.compose.components.FloatingAppCardsBackground
import com.looker.droidify.compose.components.PageIndicator
import com.looker.droidify.compose.components.premiumCardBorder
import com.looker.droidify.compose.components.verticalScrollbar
import com.looker.droidify.compose.externalApps.ExternalAppIcon
import com.looker.droidify.compose.repoList.AppLauncherIcon
import com.looker.droidify.compose.repoList.RepoIcon
import com.looker.droidify.compose.repoList.defaultRepoIcon
import com.looker.droidify.compose.repoList.defaultRepoIconRes
import com.looker.droidify.compose.repoList.repoIconRequest
import com.looker.droidify.compose.settings.BackgroundStyleSetting
import com.looker.droidify.compose.settings.InstallerTypeSetting
import com.looker.droidify.compose.settings.ThemeSetting
import com.looker.droidify.compose.settings.components.AccentColorChoices
import com.looker.droidify.compose.theme.LocalStatusBarContent
import com.looker.droidify.datastore.model.BackgroundStyle
import com.looker.droidify.datastore.model.InstallerType
import com.looker.droidify.datastore.model.Theme
import com.looker.droidify.utility.common.SdkCheck
import com.looker.droidify.utility.text.toAnnotatedString
import kotlinx.coroutines.launch

private val ContentMaxWidth = 560.dp

/** What the bar under the pages takes: the dots, then the way back and the way forward. */
private val NavigationBarHeight = 84.dp

private const val PAGE_COUNT = 4

/** How much of the header the screen's height leaves room for. */
private enum class HeaderSize { Large, Medium, Small }

private fun headerSizeFor(height: Dp): HeaderSize = when {
    height >= 780.dp -> HeaderSize.Large
    height >= 560.dp -> HeaderSize.Medium
    else -> HeaderSize.Small
}

/**
 * What a fresh install opens on, in four pages with a pager's dots under them.
 *
 * The first welcomes the person and asks, one button each, for what Omnify needs from the device:
 * notifications, the install of apps, and the installer to use. Nothing is asked out of the blue at
 * start-up any more. The second brings data over from a backup file, another device or an Omnify Beta,
 * all of it optional. The third lets them pick the look of the app, each choice applying at once. The
 * fourth asks where the apps should come from, before anything is downloaded, instead of switching
 * sources on by itself and starting to synchronise at once.
 *
 * No page scrolls as a page, whatever the screen: the sources sit in a card of their own that scrolls
 * inside itself, the header gives up room as the screen gets shorter, and a wide screen lays a page out
 * in two columns instead of one. The buttons under the pages go back and forward, and the last one
 * switches the ticked sources on, with or without any, and opens the home, which is when the first
 * synchronisation starts. The Android TV version is [com.looker.droidify.compose.tv.TvWelcomeScreen].
 */
@Composable
fun WelcomeScreen(
    viewModel: WelcomeViewModel,
    onDone: () -> Unit,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val finishing by viewModel.finishing.collectAsStateWithLifecycle()
    val installerType by viewModel.installerType.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val steps = rememberWelcomeSteps()
    val restoreSteps = rememberRestoreSteps(viewModel)
    val allChecked = items.isNotEmpty() && items.all { it.checked }
    val pagerState = rememberPagerState { PAGE_COUNT }
    val scope = rememberCoroutineScope()
    val currentPage = pagerState.currentPage

    fun goToPage(page: Int) {
        scope.launch { pagerState.animateScrollToPage(page) }
    }
    BackHandler(enabled = currentPage > 0) { goToPage(currentPage - 1) }

    // The logos of the last page are fetched while the first ones are being read, so they are there
    // when it comes.
    PrefetchWelcomeLogos(items)

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
                        when (page) {
                            0 -> WelcomeSetupPage(
                                wide = wide,
                                headerSize = headerSize,
                                steps = steps,
                                installerType = installerType,
                                onInstallerSelected = viewModel::setInstaller,
                            )

                            1 -> WelcomeRestorePage(
                                wide = wide,
                                headerSize = headerSize,
                                steps = restoreSteps,
                            )

                            2 -> WelcomeAppearancePage(
                                wide = wide,
                                headerSize = headerSize,
                                appearance = appearance,
                                onThemeSelected = viewModel::setTheme,
                                onBackgroundSelected = viewModel::setBackgroundStyle,
                                onColorSelected = viewModel::chooseAccentColor,
                                onWallpaperSelected = viewModel::useWallpaperAccent,
                            )

                            else -> WelcomeSourcesPage(
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
                    enabled = !finishing,
                    onPrevious = { goToPage(currentPage - 1) },
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
            WelcomeSetupCards(
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
            WelcomeSetupCards(
                steps = steps,
                installerType = installerType,
                onInstallerSelected = onInstallerSelected,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** The second page: bringing data over from where it already is. Nothing here is required. */
@Composable
private fun WelcomeRestorePage(
    wide: Boolean,
    headerSize: HeaderSize,
    steps: List<WelcomeStep>,
) {
    val compact = headerSize == HeaderSize.Small
    if (wide) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().padding(16.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(0.4f)) {
                WelcomePageHeading(
                    title = R.string.welcome_restore_title,
                    hint = R.string.welcome_restore_hint,
                    compact = compact,
                )
            }
            WelcomeRestoreCards(steps = steps, modifier = Modifier.weight(0.6f))
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize().padding(16.dp),
        ) {
            WelcomePageHeading(
                title = R.string.welcome_restore_title,
                hint = R.string.welcome_restore_hint,
                compact = compact,
            )
            Spacer(Modifier.height(16.dp))
            WelcomeRestoreCards(steps = steps, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

/** The third page: how the app looks. */
@Composable
private fun WelcomeAppearancePage(
    wide: Boolean,
    headerSize: HeaderSize,
    appearance: Appearance,
    onThemeSelected: (Theme) -> Unit,
    onBackgroundSelected: (BackgroundStyle) -> Unit,
    onColorSelected: (Int) -> Unit,
    onWallpaperSelected: () -> Unit,
) {
    val compact = headerSize == HeaderSize.Small
    if (wide) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().padding(16.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(0.4f)) {
                WelcomePageHeading(
                    title = R.string.welcome_appearance_title,
                    hint = R.string.welcome_appearance_hint,
                    compact = compact,
                )
            }
            WelcomeAppearanceCards(
                appearance = appearance,
                onThemeSelected = onThemeSelected,
                onBackgroundSelected = onBackgroundSelected,
                onColorSelected = onColorSelected,
                onWallpaperSelected = onWallpaperSelected,
                modifier = Modifier.weight(0.6f),
            )
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize().padding(16.dp),
        ) {
            WelcomePageHeading(
                title = R.string.welcome_appearance_title,
                hint = R.string.welcome_appearance_hint,
                compact = compact,
            )
            Spacer(Modifier.height(16.dp))
            WelcomeAppearanceCards(
                appearance = appearance,
                onThemeSelected = onThemeSelected,
                onBackgroundSelected = onBackgroundSelected,
                onColorSelected = onColorSelected,
                onWallpaperSelected = onWallpaperSelected,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** The fourth page: where the apps should come from. */
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
                WelcomePageHeading(
                    title = R.string.welcome_sources_page_title,
                    hint = R.string.welcome_sources_page_hint,
                    compact = headerSize == HeaderSize.Small,
                )
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
            WelcomePageHeading(
                title = R.string.welcome_sources_page_title,
                hint = R.string.welcome_sources_page_hint,
                compact = headerSize == HeaderSize.Small,
            )
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

/** What a page after the first is about, kept to its title when the screen is too short for the sentence. */
@Composable
private fun WelcomePageHeading(
    @StringRes title: Int,
    @StringRes hint: Int,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (!compact) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** What Omnify needs from the device, a card each: the permissions with their button, then the installer
 *  with a word for those who do not know what to pick. Scrolls only if a very small screen or a very large
 *  font leaves it no other way to fit. */
@Composable
private fun WelcomeSetupCards(
    steps: List<WelcomeStep>,
    installerType: InstallerType,
    onInstallerSelected: (InstallerType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        steps.forEach { step -> WelcomeStepCard(step) }
        WelcomeCard {
            InstallerTypeSetting(
                icon = painterResource(R.drawable.ic_tabler_package),
                selectedInstaller = installerType,
                onInstallerSelected = onInstallerSelected,
                supportingText = stringResource(R.string.welcome_installer_hint),
            )
        }
    }
}

/** The ways to bring data over, a card each. Scrolls only if a very small screen or a very large font
 *  leaves it no other way to fit. */
@Composable
private fun WelcomeRestoreCards(steps: List<WelcomeStep>, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        steps.forEach { step -> WelcomeStepCard(step) }
    }
}

/** The look, a card each: the theme and the background as rows opening their lists, then the accent
 *  colours laid out to be tapped. Every choice applies at once, so the page itself shows the result.
 *  Scrolls only if a very small screen or a very large font leaves it no other way to fit. */
@Composable
private fun WelcomeAppearanceCards(
    appearance: Appearance,
    onThemeSelected: (Theme) -> Unit,
    onBackgroundSelected: (BackgroundStyle) -> Unit,
    onColorSelected: (Int) -> Unit,
    onWallpaperSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        WelcomeCard {
            Column {
                ThemeSetting(
                    icon = painterResource(R.drawable.ic_tabler_paint),
                    selectedTheme = appearance.theme,
                    onThemeSelected = onThemeSelected,
                )
                BackgroundStyleSetting(
                    selectedStyle = appearance.backgroundStyle,
                    onStyleSelected = onBackgroundSelected,
                )
            }
        }
        WelcomeCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.theme_color),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                AccentColorChoices(
                    selectedColor = appearance.themeColor,
                    dynamicEnabled = appearance.dynamicTheme,
                    showWallpaperOption = SdkCheck.isSnowCake,
                    onColorSelected = onColorSelected,
                    onWallpaperSelected = onWallpaperSelected,
                )
            }
        }
    }
}

/** The frame of a card on these pages: the usual bordered white card. */
@Composable
private fun WelcomeCard(
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    // The border lives on the outer Box: the Surface's own background would paint over it (see
    // premiumCardBorder's use in HeroCard).
    Box(modifier = modifier.fillMaxWidth().then(premiumCardBorder(shape))) {
        Surface(
            modifier = Modifier.fillMaxWidth().then(if (fillHeight) Modifier.fillMaxHeight() else Modifier),
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            content = content,
        )
    }
}

/**
 * One permission: what it is, and a button to give it, or a tick once it is given. The button sits in
 * the title's row and the sentence runs under both, so neither is squeezed by the other.
 */
@Composable
private fun WelcomeStepCard(step: WelcomeStep) {
    WelcomeCard {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(
                painter = painterResource(step.icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp).size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(step.title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (step.done) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = stringResource(step.doneLabel),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    } else {
                        FilledTonalButton(onClick = step.onClick, enabled = step.enabled) {
                            Text(stringResource(step.actionLabel))
                        }
                    }
                }
                Text(
                    text = stringResource(step.description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    WelcomeCard(modifier = modifier, fillHeight = true) {
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
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 8.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScrollbar(listState),
            ) {
                items(items, key = { it.id }) { item ->
                    WelcomeSourceRow(item = item, enabled = enabled, onToggle = { onToggle(item.id) })
                }
            }
        }
    }
}

/** One source: its logo, what it is, and a tick. The whole row is the control, and a ticked one is tinted
 *  with the accent, which says what is on without any line between the rows. */
@Composable
private fun WelcomeSourceRow(
    item: WelcomeItem,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (item.checked) tint else Color.Transparent)
            .toggleable(
                value = item.checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
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
 * Under the pages: the dots, then the way back (greyed out on the first page, so the two buttons never change size) and the way forward. The
 * forward button names the step: "Next" until the last page, then what finishing amounts to.
 */
@Composable
private fun WelcomeNavigationBar(
    currentPage: Int,
    anyChecked: Boolean,
    enabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val last = currentPage == PAGE_COUNT - 1
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
    ) {
        PageIndicator(pageCount = PAGE_COUNT, currentPage = currentPage)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
        ) {
            val padding = PaddingValues(horizontal = 12.dp)
            MutedButton(
                onClick = onPrevious,
                enabled = currentPage > 0 && enabled,
                contentPadding = padding,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Text(stringResource(R.string.welcome_previous), maxLines = 1, softWrap = false)
            }
            Button(
                onClick = onNext,
                enabled = enabled,
                contentPadding = padding,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Text(
                    text = stringResource(
                        when {
                            !last -> R.string.welcome_next
                            anyChecked -> R.string.welcome_continue
                            else -> R.string.welcome_continue_without_sources
                        },
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** The request for a source's logo when it comes from the network, the very one [WelcomeSourceIcon] makes. */
private fun WelcomeItem.logoRequest(context: Context): ImageRequest? = when (val kind = kind) {
    WelcomeKind.OmnifySource, WelcomeKind.OmnifyAccount -> null
    is WelcomeKind.FdroidRepository -> {
        val address = kind.repo.address
        // A bundled glyph wins over any address, as in RepoIcon.
        if (defaultRepoIconRes(address) != null) {
            null
        } else {
            (defaultRepoIcon(address)?.takeIf { it.isNotBlank() } ?: kind.repo.icon?.path)
                ?.let { repoIconRequest(context, it, kind.repo.versionInfo?.timestamp) }
        }
    }

    is WelcomeKind.TvApp -> kind.app.repoIconUrl?.let { ImageRequest.Builder(context).data(it).build() }
}

/**
 * Starts fetching every source's logo as soon as the sources are known, so that none is still arriving
 * when the page that shows them is reached. It only fills the image cache; what is drawn is still
 * [WelcomeSourceIcon]. Keyed on which sources there are, so ticking one does not fetch everything again.
 */
@Composable
internal fun PrefetchWelcomeLogos(items: List<WelcomeItem>) {
    val context = LocalContext.current
    val ids = items.map { it.id }
    LaunchedEffect(ids) {
        val loader = SingletonImageLoader.get(context)
        items.forEach { item -> item.logoRequest(context)?.let { loader.enqueue(it) } }
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

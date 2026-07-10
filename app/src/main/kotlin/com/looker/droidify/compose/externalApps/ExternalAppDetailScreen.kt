package com.looker.droidify.compose.externalApps

import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.components.BackButton
import com.looker.droidify.compose.components.DescriptionTranslation
import com.looker.droidify.compose.components.HeroCard
import com.looker.droidify.compose.components.HeroStatsRow
import com.looker.droidify.compose.components.InstallVersionDialog
import com.looker.droidify.compose.components.LinkRow
import com.looker.droidify.compose.components.RootBadge
import com.looker.droidify.compose.components.ScrollToTopFab
import com.looker.droidify.compose.components.SectionSeparator
import com.looker.droidify.compose.components.ShowMoreRow
import com.looker.droidify.compose.components.SplitViewToggleAction
import com.looker.droidify.compose.components.SupportedLanguages
import com.looker.droidify.compose.components.SupportedLanguagesSection
import com.looker.droidify.compose.components.TranslateAction
import com.looker.droidify.compose.components.heroFooter
import com.looker.droidify.compose.components.tvDpadDownTo
import com.looker.droidify.compose.components.tvPageScroll
import com.looker.droidify.compose.theme.AccentBarHeight
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.compose.theme.accentTopAppBarColors
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.Release
import com.looker.droidify.external.apkFileName
import com.looker.droidify.external.apkFileSize
import com.looker.droidify.external.apkVersionLabel
import com.looker.droidify.network.DataSize
import com.looker.droidify.utility.common.RootDetection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExternalAppDetailScreen(
    appKey: String,
    viewModel: ExternalAppsViewModel,
    onBackClick: () -> Unit,
) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val installStates by viewModel.installStates.collectAsStateWithLifecycle()
    val installedVersions by viewModel.installedVersions.collectAsStateWithLifecycle()
    val readme by viewModel.readme.collectAsStateWithLifecycle()
    val readmeError by viewModel.readmeError.collectAsStateWithLifecycle()
    val readmeTranslation by viewModel.readmeTranslation.collectAsStateWithLifecycle()
    val translationEnabled by viewModel.translationEnabled.collectAsStateWithLifecycle()
    val readmeJavaScriptEnabled by viewModel.readmeJavaScriptEnabled.collectAsStateWithLifecycle()
    val releaseHistory by viewModel.releaseHistory.collectAsStateWithLifecycle()
    val issueTrackerLink by viewModel.issueTrackerLink.collectAsStateWithLifecycle()
    val changelogLink by viewModel.changelogLink.collectAsStateWithLifecycle()
    val changelogHtml by viewModel.changelogHtml.collectAsStateWithLifecycle()
    val changelogUnavailable by viewModel.changelogUnavailable.collectAsStateWithLifecycle()
    var showChangelog by remember { mutableStateOf(false) }
    val supportedLanguages by viewModel.supportedLanguages.collectAsStateWithLifecycle()
    val splitViewSettingEnabled by viewModel.splitViewEnabled.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.reconcileInstalledLabels()
    }
    // Re-query install state on every resume — in particular when returning from the system uninstall
    // dialog — so the buttons switch from Open/Uninstall back to Install without having to leave and
    // re-open the screen (installManager.state alone doesn't report a system uninstall).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshInstalled()
    }

    val app = apps.firstOrNull { it.key == appKey }
    val installedVersion = installedVersions[appKey]
    // Read straight off installedVersions (not the separately-collected installedKeys StateFlow) so the
    // button and the footer's "Installé : …" text always agree within the same composition — two
    // independently-collected StateFlows can otherwise land on different frames for a moment.
    val isInstalled = installedVersion != null
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    // Hoisted above the Scaffold (not inside its content lambda) so both the content column and the
    // scroll-to-top FAB can read/drive the same scroll position.
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    // Where the versions section actually lands once composed (in the scrolling Column's own
    // coordinate space), so the hero card's "see all versions" link can jump straight to it.
    var versionsAnchorY by remember { mutableStateOf(0) }

    // Play Store-style two-pane layout: only on a tablet-width screen in landscape (never on phones, TV,
    // or portrait), and only when the user hasn't turned the feature off entirely in Settings. A small
    // top-bar button (see splitViewManuallyOff below) additionally lets the user switch back to the
    // normal single-column layout without touching Settings, for this viewing only. Mirrors the F-Droid
    // catalogue detail screen's own check (see AppDetailScreen) so both behave identically.
    val configuration = LocalConfiguration.current
    val splitViewAvailable = !LocalIsTelevision.current &&
        configuration.screenWidthDp >= 600 &&
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        splitViewSettingEnabled
    var splitViewManuallyOff by remember { mutableStateOf(false) }
    val useSplitView = splitViewAvailable && !splitViewManuallyOff

    // Download the project README (as HTML) once the app is known, to fill out the detail screen.
    LaunchedEffect(app?.key) {
        app?.let { viewModel.loadReadme(it) }
    }
    // Recent releases, for the "choose a version to install" list at the bottom of the screen.
    LaunchedEffect(app?.key) {
        app?.let { viewModel.loadReleaseHistory(it) }
    }
    // The Links section's "Issue tracker" and "Changelog" rows.
    LaunchedEffect(app?.key) {
        app?.let { viewModel.loadIssueTrackerAndChangelog(it) }
    }
    // The "supported languages" section — re-checked when the install state changes too, so it
    // switches from the remote-verified check to the installed APK's own locales right after install.
    LaunchedEffect(app?.key, isInstalled) {
        app?.let { viewModel.loadSupportedLanguages(it, isInstalled) }
    }

    // TV / D-pad: the top bar doesn't release focus downward on its own, and startup focus was never
    // requested at all — mirroring the F-Droid catalogue detail screen's primaryActionFocusRequester so
    // both screens behave identically. No effect on touch.
    val isTelevision = LocalIsTelevision.current
    val primaryActionFocusRequester = remember { FocusRequester() }
    if (isTelevision) {
        LaunchedEffect(app?.key) {
            repeat(20) {
                if (runCatching { primaryActionFocusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(50)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = accentTopAppBarColors(),
                expandedHeight = AccentBarHeight,
                modifier = if (isTelevision) {
                    Modifier.tvDpadDownTo(primaryActionFocusRequester)
                } else {
                    Modifier
                },
                title = {
                    Text(
                        text = app?.label.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton(onBackClick) },
                actions = {
                    if (splitViewAvailable) {
                        SplitViewToggleAction(
                            splitView = useSplitView,
                            onToggle = { splitViewManuallyOff = !splitViewManuallyOff },
                        )
                    }
                    if (app != null) {
                        IconButton(
                            onClick = {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    putExtra(Intent.EXTRA_TEXT, app.webUrl)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, null))
                            },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_share),
                                contentDescription = stringResource(R.string.share),
                            )
                        }
                    }
                    // Only offer translation once there's a README to translate and an engine is set.
                    if (translationEnabled && readme != null) {
                        TranslateAction(
                            translation = readmeTranslation,
                            onTranslate = { readme?.let(viewModel::translateReadme) },
                            onShowOriginal = viewModel::showOriginalReadme,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(viewModel.snackbarHostState) },
        floatingActionButton = { ScrollToTopFab(scrollState) },
    ) { contentPadding ->
        if (app == null) {
            // The source was removed (or not loaded yet); nothing to show.
            Spacer(Modifier.padding(contentPadding))
            return@Scaffold
        }
        // A release the user tapped in the version list, awaiting confirmation to install (null = no
        // dialog). Unlike the F-Droid catalogue's version dialog, downgrades are never flagged here: a
        // release carries no version code ahead of the download, so there's nothing to compare against
        // the installed one before the user confirms.
        var versionToInstall by remember { mutableStateOf<Release?>(null) }
        versionToInstall?.let { release ->
            InstallVersionDialog(
                versionName = release.tag,
                isDowngrade = false,
                onInstall = {
                    viewModel.installVersion(app, release)
                    versionToInstall = null
                },
                onUninstall = {},
                onDismiss = { versionToInstall = null },
            )
        }
        if (showChangelog) {
            ChangelogDialog(
                html = changelogHtml,
                unavailable = changelogUnavailable,
                baseUrl = app.readmeWebBaseUrl,
                javaScriptEnabled = readmeJavaScriptEnabled,
                onDismiss = {
                    showChangelog = false
                    viewModel.dismissChangelog()
                },
            )
        }
        // Everything (the icon/name/versions/actions block AND the README) scrolls as one — only the
        // top bar stays fixed, except in the tablet-landscape two-pane layout below, where the hero card
        // moves to its own fixed left pane. The WebView reports its content height (in
        // ExternalAppDetailBody) so it can be sized exactly instead of owning a second, separate scroll.
        // Show the translated HTML when present, otherwise the original. Both render in the WebView so a
        // translation keeps the README's images, headings, lists and links.
        val readmeHtml = (readmeTranslation as? DescriptionTranslation.Translated)?.description ?: readme
        // scrollState is hoisted above the Scaffold (used by the page-scroll TV modifier below and by
        // the scroll-to-top FAB). viewportPx drives the page step for TV D-pad paging.
        var viewportPx by remember { mutableStateOf(0) }

        // The repo's release tag (e.g. "v2.5.0") often doesn't match the APK's own version — the file
        // name usually does (e.g. "GlassKeep-1.4.6.apk" for a "v2.5.0" release), so that's what's shown
        // as the hero "Version" whenever a build is available, falling back to the tag otherwise.
        val heroVersion = app.latestApkName?.let { apkVersionLabel(it) } ?: app.latestTag
        // Mirrors the F-Droid catalogue's "Taille" stat; null (hidden) when the provider's release
        // API doesn't expose a file size (GitLab's release link assets carry none).
        val heroSize = app.latestApkSize?.let { DataSize(it).toString() }
        LaunchedEffect(app.key, app.latestApkSize) {
            Log.d("ExternalAppDetailScreen", "${app.key}: latestApkSize=${app.latestApkSize} heroSize=$heroSize")
        }
        // Fuzzy but shared with the F-Droid catalogue's own check (see RootDetection): an external
        // source has no manifest permissions to read, so this only has the app's own text to go on
        // — its name and README (once loaded; the badge simply isn't shown yet before then).
        val isRootCompatible = remember(app.key, readmeHtml) {
            RootDetection.textIndicatesRoot("${app.label} ${readmeHtml.orEmpty()}")
        }

        // The installed version, if any — folded into the card's footer, mirroring how the F-Droid
        // catalogue card shows its installed version there.
        val footerText = installedVersion?.let {
            stringResource(R.string.external_installed_version, it)
        }

        // Split view only: the left pane (hero card + links + versions) scrolls on its own, separately
        // from the right pane, so a long version list there doesn't overflow the pane uncontrolled. Its
        // own "see all versions" anchor, parallel to versionsAnchorY above.
        val leftPaneScrollState = rememberScrollState()
        var leftPaneVersionsAnchorY by remember { mutableStateOf(0) }

        // The hero card content is identical in both layouts below (nothing moved out of it) — kept as
        // one lambda so the single-column and split-view branches can never drift apart on it.
        val headerCard: @Composable () -> Unit = {
            HeroCard(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                icon = {
                    ExternalAppIcon(app = app, isInstalled = isInstalled, size = 88.dp)
                },
                name = app.label,
                subtitle = stringResource(R.string.by_author_FORMAT, app.owner),
                badge = if (isRootCompatible) { { RootBadge() } } else null,
                stats = {
                    HeroStatsRow(
                        version = heroVersion,
                        size = heroSize,
                        onSourceCodeClick = { uriHandler.openUri(app.webUrl) },
                    )
                },
                actions = {
                    ExternalLifecycleActions(
                        app = app,
                        downloadStatus = downloads[appKey],
                        installState = installStates[appKey],
                        isInstalled = isInstalled,
                        onInstallOrUpdate = { viewModel.installOrUpdate(app) },
                        onLaunch = { viewModel.launch(app) },
                        onUninstall = { viewModel.uninstall(app) },
                        onCancel = { viewModel.cancel(app) },
                        primaryActionFocusRequester = primaryActionFocusRequester,
                    )
                },
                footer = heroFooter(
                    infoText = footerText,
                    onViewVersionsClick = if (!releaseHistory.isNullOrEmpty()) {
                        {
                            coroutineScope.launch {
                                if (useSplitView) {
                                    leftPaneScrollState.animateScrollTo(leftPaneVersionsAnchorY)
                                } else {
                                    scrollState.animateScrollTo(versionsAnchorY)
                                }
                            }
                        }
                    } else {
                        null
                    },
                ),
            )
        }

        if (useSplitView) {
            // Tablet landscape only (see useSplitView): a Play Store-style two-pane layout. The hero
            // card, links and versions sit in the left pane (scrolling on its own if that content runs
            // long), while the README and everything else scrolls independently in the right pane.
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxHeight()
                        .verticalScroll(leftPaneScrollState),
                ) {
                    headerCard()
                    ExternalLinksSection(
                        issueTrackerLink = issueTrackerLink,
                        changelogLink = changelogLink,
                        onChangelogClick = {
                            showChangelog = true
                            viewModel.loadChangelogHtml(app)
                        },
                    )
                    supportedLanguages?.let { languages ->
                        Spacer(Modifier.height(8.dp))
                        SupportedLanguagesSection(languages = languages)
                    }
                    // ExternalVersionsSection already opens with its own SectionSeparator (matching the
                    // catalogue's own spacing) — no second one needed here.
                    ExternalVersionsSection(
                        app = app,
                        releaseHistory = releaseHistory,
                        onVersionClick = { versionToInstall = it },
                        onAnchorPositioned = { leftPaneVersionsAnchorY = it },
                    )
                }
                VerticalDivider()
                Column(
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                        .onSizeChanged { viewportPx = it.height }
                        .verticalScroll(scrollState),
                ) {
                    ExternalAppDetailBody(
                        app = app,
                        isInstalled = isInstalled,
                        readmeHtml = readmeHtml,
                        readmeError = readmeError,
                        readmeJavaScriptEnabled = readmeJavaScriptEnabled,
                        viewportPx = viewportPx,
                        scrollState = scrollState,
                        issueTrackerLink = issueTrackerLink,
                        changelogLink = changelogLink,
                        onChangelogClick = {
                            showChangelog = true
                            viewModel.loadChangelogHtml(app)
                        },
                        supportedLanguages = supportedLanguages,
                        releaseHistory = releaseHistory,
                        onVersionClick = { versionToInstall = it },
                        onAnchorPositioned = { versionsAnchorY = it },
                        showSidebarSections = false,
                        showLeadingSeparator = false,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .onSizeChanged { viewportPx = it.height }
                    .verticalScroll(scrollState),
            ) {
                headerCard()
                ExternalAppDetailBody(
                    app = app,
                    isInstalled = isInstalled,
                    readmeHtml = readmeHtml,
                    readmeError = readmeError,
                    readmeJavaScriptEnabled = readmeJavaScriptEnabled,
                    viewportPx = viewportPx,
                    scrollState = scrollState,
                    issueTrackerLink = issueTrackerLink,
                    changelogLink = changelogLink,
                    onChangelogClick = {
                        showChangelog = true
                        viewModel.loadChangelogHtml(app)
                    },
                    supportedLanguages = supportedLanguages,
                    releaseHistory = releaseHistory,
                    onVersionClick = { versionToInstall = it },
                    onAnchorPositioned = { versionsAnchorY = it },
                    showSidebarSections = true,
                    showLeadingSeparator = true,
                )
            }
        }
    }
}

/**
 * Everything on the external-app detail screen after the hero card: the pre-install notice, README,
 * links, supported languages, and the version list. Extracted so the single-column and tablet-landscape
 * two-pane layouts in [ExternalAppDetailScreen] call the exact same content instead of two copies that
 * could drift apart. Deliberately emits its composables directly with no wrapping Column of its own, so
 * they land as direct children of whichever scrolling Column calls it — keeping the version list's
 * scroll anchor (see [onAnchorPositioned]) correct in both layouts.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExternalAppDetailBody(
    app: ExternalApp,
    isInstalled: Boolean,
    readmeHtml: String?,
    readmeError: String?,
    readmeJavaScriptEnabled: Boolean,
    viewportPx: Int,
    scrollState: ScrollState,
    issueTrackerLink: LinkCheckState?,
    changelogLink: LinkCheckState?,
    onChangelogClick: () -> Unit,
    supportedLanguages: SupportedLanguages?,
    releaseHistory: List<Release>?,
    onVersionClick: (Release) -> Unit,
    onAnchorPositioned: (Int) -> Unit,
    // False in split view: the tablet-landscape left pane shows links, supported languages and
    // versions itself (see ExternalAppDetailScreen), so this body must not repeat them.
    showSidebarSections: Boolean,
    // False in split view: the hero card (which this separator normally sits right below) is in the
    // other, left pane there, so a separator at the very top of this one would float with nothing
    // above it.
    showLeadingSeparator: Boolean,
) {
    val density = LocalDensity.current
    // Keyed on app.key (stable for this screen), NOT on the html: the WebView captures its
    // height callback once, so the callback must keep targeting the same state instance. The
    // WebView re-reports its height every time the document reloads, so translating or reverting
    // resizes correctly on its own.
    var readmeHeightPx by remember(app.key) { mutableStateOf(0) }

    if (!isInstalled) {
        PreInstallNotice(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }

    if (showLeadingSeparator) {
        Spacer(Modifier.height(20.dp))
        SectionSeparator()
        Spacer(Modifier.height(4.dp))
    }

    // README — sized to its content (it doesn't scroll itself) so it scrolls with the rest.
    // GitHub leaves repo-relative image paths un-rewritten, so the WebView resolves them
    // against the raw content host. While it loads, show a spinner instead of empty space.
    if (readmeHtml != null) {
        // Collapsed to fill the space left below it down to the bottom of the screen — same idea as
        // the F-Droid catalogue's description. A WebView has no line count to cap, so a pixel height is
        // capped instead; Compose coerces the WebView's own (larger) requested height down to it, so
        // the extra content is simply clipped rather than scrolled internally. The "Show more" button
        // is a real button, shown once and never again once tapped (no collapsing back). Split view: the
        // right pane is dedicated space for this content, so it's never collapsed there
        // (showSidebarSections is false only in that pane).
        var readmeExpanded by remember(app.key) { mutableStateOf(!showSidebarSections) }
        var readmeTopY by remember(app.key) { mutableStateOf(0) }
        val fullReadmeHeight = if (readmeHeightPx > 0) with(density) { readmeHeightPx.toDp() } else 600.dp
        val collapsedReadmeHeight = if (viewportPx > 0 && readmeTopY > 0) {
            with(density) { (viewportPx - readmeTopY).coerceAtLeast(0).toDp() }
        } else {
            README_COLLAPSED_HEIGHT
        }
        val readmeCanCollapse = showSidebarSections && fullReadmeHeight > collapsedReadmeHeight
        // On TV this Box is the single focus stop for the whole README: landing here, the D-pad
        // pages the screen up/down through it (the WebView itself is non-focusable on TV, so the
        // remote no longer steps over its links and images). A plain wrapper on touch.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { readmeTopY = it.positionInParent().y.toInt() }
                .heightIn(max = if (readmeExpanded || !readmeCanCollapse) fullReadmeHeight else collapsedReadmeHeight)
                .clipToBounds()
                .tvPageScroll(scrollState, (viewportPx * 0.85f).toInt()),
        ) {
            ReadmeWebView(
                html = readmeHtml,
                baseUrl = app.readmeWebBaseUrl,
                javaScriptEnabled = readmeJavaScriptEnabled,
                onContentHeight = { readmeHeightPx = it },
                scrollState = scrollState,
                modifier = Modifier
                    .fillMaxWidth()
                    // Until the content height is known, give it a sensible height so it can render
                    // and measure; then snap to the exact height.
                    .height(fullReadmeHeight),
            )
        }
        if (!readmeExpanded && readmeCanCollapse) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Button(
                    onClick = { readmeExpanded = true },
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text(stringResource(R.string.show_more))
                }
            }
        }
    } else if (readmeError != null) {
        // The fetch already failed (most often the anonymous GitHub rate limit) — say so instead
        // of leaving the spinner running forever with no way to tell it apart from "still loading".
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = readmeError.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularWavyProgressIndicator(modifier = Modifier.size(36.dp))
        }
    }

    if (showSidebarSections) {
        Spacer(Modifier.height(16.dp))
        ExternalLinksSection(
            issueTrackerLink = issueTrackerLink,
            changelogLink = changelogLink,
            onChangelogClick = onChangelogClick,
        )
    }

    // Same reliable, real-UI-language check as the F-Droid catalogue's — but with no store-
    // listing metadata to fall back to for an external source, this section only shows once
    // there's a confirmed answer, instead of ever showing a guess.
    if (showSidebarSections) {
        supportedLanguages?.let { languages ->
            Spacer(Modifier.height(8.dp))
            SupportedLanguagesSection(languages = languages)
        }
    }

    if (showSidebarSections) {
        ExternalVersionsSection(
            app = app,
            releaseHistory = releaseHistory,
            onVersionClick = onVersionClick,
            onAnchorPositioned = onAnchorPositioned,
        )
    }
}

/** How tall the collapsed README preview is before a "Show more" tap reveals the rest. */
private val README_COLLAPSED_HEIGHT = 320.dp

/**
 * "Issue tracker" and "Changelog" — an external source has no index metadata for these like the
 * F-Droid catalogue does, so they're resolved live from the provider. Always shown (not hidden while
 * resolving/absent) so the page doesn't jump around as the checks complete; a still-loading or
 * genuinely absent link reads as a plain "…" / explanatory row instead. Extracted so both the
 * single-column body and the tablet-landscape split view's left pane render the exact same content.
 */
@Composable
private fun ExternalLinksSection(
    issueTrackerLink: LinkCheckState?,
    changelogLink: LinkCheckState?,
    onChangelogClick: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = Modifier.fillMaxWidth()) {
        LinkRow(
            iconRes = R.drawable.ic_bug_report,
            title = stringResource(R.string.issue_tracker),
            url = issueTrackerLink?.url,
            unavailableText = stringResource(
                if (issueTrackerLink == null) R.string.loading else R.string.external_no_issue_tracker,
            ),
            onClick = issueTrackerLink?.url?.let { url -> { uriHandler.openUri(url) } },
        )
        LinkRow(
            iconRes = R.drawable.ic_history,
            title = stringResource(R.string.changelog),
            url = changelogLink?.url,
            unavailableText = stringResource(
                if (changelogLink == null) R.string.loading else R.string.external_no_changelog,
            ),
            // Opened in-app (see ChangelogDialog), same as the README, instead of sending
            // the user to the browser to read what's new.
            onClick = changelogLink?.url?.let { { onChangelogClick() } },
        )
    }
}

/**
 * Recent releases the user can pick a specific version to install from — same idea as the F-Droid
 * catalogue's version list. Position-tracked so the hero card's "see all versions" link can scroll
 * straight to it. Extracted so both the single-column body and the tablet-landscape split view's left
 * pane render the exact same content.
 */
@Composable
private fun ExternalVersionsSection(
    app: ExternalApp,
    releaseHistory: List<Release>?,
    onVersionClick: (Release) -> Unit,
    onAnchorPositioned: (Int) -> Unit,
) {
    if (releaseHistory.isNullOrEmpty()) return
    Column(
        modifier = Modifier.onGloballyPositioned {
            onAnchorPositioned(it.positionInParent().y.toInt())
        },
    ) {
        Spacer(Modifier.height(20.dp))
        SectionSeparator()
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.versions),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // Collapsed to the newest few by default — same as the F-Droid catalogue's version
        // list, so a long history doesn't turn the whole page into an endless scroll.
        var versionsExpanded by remember(app.key) { mutableStateOf(false) }
        val visibleCount = if (versionsExpanded) releaseHistory.size else VERSIONS_COLLAPSED_COUNT
        releaseHistory.take(visibleCount).forEach { release ->
            ReleaseVersionItem(
                release = release,
                apkName = release.apkFileName(filter = app.apkFilter),
                apkSize = release.apkFileSize(filter = app.apkFilter),
                isSuggested = release.tag == app.latestTag,
                isInstalled = release.tag == app.installedTag,
                onClick = { onVersionClick(release) },
            )
        }
        if (releaseHistory.size > VERSIONS_COLLAPSED_COUNT) {
            ShowMoreRow(
                hiddenCount = releaseHistory.size - VERSIONS_COLLAPSED_COUNT,
                expanded = versionsExpanded,
                onToggle = { versionsExpanded = !versionsExpanded },
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Versions shown before the "show more" toggle, matching the F-Droid catalogue's version list. */
private const val VERSIONS_COLLAPSED_COUNT = 5

/** Tells the user that the name/icon/version shown are the repository's until the app is installed
 *  (a release carries no app metadata, so the real ones are only known once the APK is on-device). */
@Composable
private fun PreInstallNotice(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Outlined.Info, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.external_preinstall_notice),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

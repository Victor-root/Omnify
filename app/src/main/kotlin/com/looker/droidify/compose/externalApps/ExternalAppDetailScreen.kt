package com.looker.droidify.compose.externalApps

import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.components.BackButton
import com.looker.droidify.compose.appDetail.DownloadStatus
import com.looker.droidify.compose.components.DescriptionTranslation
import com.looker.droidify.compose.components.FloatingAppCardsBackground
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
import com.looker.droidify.compose.components.tvDpadKeyLog
import com.looker.droidify.compose.components.tvPageScroll
import com.looker.droidify.compose.components.tvReadable
import com.looker.droidify.compose.theme.AccentBarHeight
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.compose.theme.accentTopAppBarColors
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.Release
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.external.apkFileName
import com.looker.droidify.external.apkFileSize
import com.looker.droidify.external.apkVersionLabel
import com.looker.droidify.external.compareVersionStrings
import com.looker.droidify.network.DataSize
import com.looker.droidify.utility.common.RootDetection
import com.looker.droidify.utility.common.extension.openAppInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalFoundationApi::class,
)
@Composable
fun ExternalAppDetailScreen(
    appKey: String,
    viewModel: ExternalAppsViewModel,
    onBackClick: () -> Unit,
) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val downloadTargetTag by viewModel.downloadTargetTag.collectAsStateWithLifecycle()
    val installStates by viewModel.installStates.collectAsStateWithLifecycle()
    val installedVersions by viewModel.installedVersions.collectAsStateWithLifecycle()
    val installSources by viewModel.installSources.collectAsStateWithLifecycle()
    val signatureConflict by viewModel.signatureConflict.collectAsStateWithLifecycle()
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
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()

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

    signatureConflict?.let { conflict ->
        val conflictAppName = app?.label ?: appKey
        val titleRes = if (conflict.isSystemApp) {
            R.string.signature_conflict_system_title
        } else {
            R.string.signature_conflict_title
        }
        val messageRes = if (conflict.isSystemApp) {
            R.string.signature_conflict_system_app
        } else {
            R.string.install_failed_signature_mismatch
        }
        AlertDialog(
            onDismissRequest = viewModel::dismissSignatureConflict,
            title = { Text(stringResource(titleRes)) },
            text = { Text(stringResource(messageRes, conflictAppName)) },
            confirmButton = {
                if (conflict.isSystemApp) {
                    // A system app can't be uninstalled — nothing to do but acknowledge.
                    TextButton(onClick = viewModel::dismissSignatureConflict) {
                        Text(stringResource(android.R.string.ok))
                    }
                } else {
                    TextButton(
                        onClick = {
                            app?.let(viewModel::uninstall)
                            viewModel.dismissSignatureConflict()
                        },
                    ) { Text(stringResource(R.string.uninstall)) }
                }
            },
            dismissButton = {
                if (!conflict.isSystemApp) {
                    TextButton(onClick = viewModel::dismissSignatureConflict) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            },
        )
    }

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
    // TV D-pad diagnostics: logs every scroll-position change regardless of which focus transition (if
    // any) caused it — the tvFocus* modifiers' own logs only fire on a focus change, so a scroll jump
    // that happens WITHOUT one (e.g. a stray BringIntoView call, or something scrolling as a side effect
    // rather than a direct consequence of a logged focus move) would otherwise be invisible. Temporary,
    // see TvFocus.kt's own debug-logging doc comment.
    LaunchedEffect(Unit) {
        snapshotFlow { scrollState.value }.collect { value ->
            Log.d(
                "TvFocusDebug",
                "ExternalAppDetailScreen scrollState -> $value at ${System.currentTimeMillis()}",
            )
        }
    }
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
    // requested at all — mirroring the F-Droid catalogue detail screen so both screens behave
    // identically. No effect on touch.
    val isTelevision = LocalIsTelevision.current
    // Startup focus (and the top bar's down-escape) lands on the primary action button (Install / Update
    // / Launch). It used to target the favourite heart instead, because the heart sits at the very top
    // of the hero card and landing there could never trigger Compose's own scroll-into-view — unlike the
    // primary button further down, whose relocation could overshoot while the page's layout was still
    // settling. That scroll bug is now fixed at the scroll level itself, so it no longer needs the heart
    // as a workaround.
    val primaryActionFocusRequester = remember { FocusRequester() }
    // TV: whether the user has pressed any key on this screen yet. Once true, focus is entirely theirs —
    // nothing below may redirect it again. Set from the screen-root key handler (see the Scaffold
    // modifier below), which sees every key press regardless of what currently has focus.
    var userInteracted by remember { mutableStateOf(false) }
    // TV: true for a short window right after each "down" press — read by the BringIntoViewSpec override
    // further down, so its own doc comment explains why this exists.
    var suppressBringIntoView by remember { mutableStateOf(false) }
    if (isTelevision) {
        LaunchedEffect(app?.key) {
            repeat(20) {
                val result = runCatching { primaryActionFocusRequester.requestFocus() }
                Log.d(
                    "TvFocusDebug",
                    "ExternalAppDetailScreen startup retry #$it: primaryActionFocusRequester.requestFocus() " +
                        "success=${result.isSuccess} at ${System.currentTimeMillis()}",
                )
                if (result.isSuccess) return@LaunchedEffect
                delay(50)
            }
        }
    }

    Scaffold(
        // TV only: the remote's alternate "menu" key (e.g. the Nvidia Shield's, which opens Android TV's
        // own quick settings from the home screen) opens this app's Android "App info" management page —
        // the same target as the gear on the hero card — when the app is installed. Attached at the
        // screen root (not just the top bar) so it fires no matter which element currently has focus; see
        // the equivalent choice on AppListScreen's Scaffold for why (topBar/content are siblings, not
        // ancestor/descendant).
        modifier = if (isTelevision) {
            Modifier
                .tvDpadKeyLog("ExternalAppDetailScreen-root")
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        // Marks startup focus as "settled" — see userInteracted and the TopAppBar's own
                        // onFocusChanged below, which stop correcting focus the moment this is true.
                        if (!userInteracted) {
                            Log.d(
                                "TvFocusDebug",
                                "ExternalAppDetailScreen-root: userInteracted set true by ${event.key} at " +
                                    "${System.currentTimeMillis()}",
                            )
                        }
                        userInteracted = true
                    }
                    // TV: suppress scroll-into-view for a short window after "down". Compose's own "bring
                    // the newly focused item into view" reflex fires on every plain focus change, not just
                    // the README's own explicit page-scroll hand-off — confirmed via real logcat moving
                    // between the short rows below the README (links, languages, versions): each one nudges
                    // scrollState back by tens to ~100px even though the previous position was already
                    // fully valid, reading as the screen sliding backward right after the user pressed
                    // down. Read by the BringIntoViewSpec override further down (see its own doc comment).
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        suppressBringIntoView = true
                        coroutineScope.launch {
                            delay(320)
                            suppressBringIntoView = false
                        }
                    }
                    val packageName = app?.packageName
                    if (isInstalled && packageName != null &&
                        event.type == KeyEventType.KeyDown && event.key == Key.Menu
                    ) {
                        context.openAppInfo(packageName)
                        true
                    } else {
                        false
                    }
                }
        } else {
            Modifier
        },
        topBar = {
            TopAppBar(
                colors = accentTopAppBarColors(),
                expandedHeight = AccentBarHeight,
                modifier = if (isTelevision) {
                    Modifier
                        .tvDpadDownTo(primaryActionFocusRequester, debugLabel = "external-topappbar")
                        // TV: self-healing net for startup focus (userInteracted still false) AND for a
                        // second, distinct case confirmed by a real device log: pressing "up" deep in the
                        // scrolled content (e.g. from the last version row, or from the README once it
                        // releases D-pad paging) can make Compose's own directional focus search jump
                        // straight to this top bar instead of the actually-adjacent element — its default
                        // 2D search picks whatever candidate is geometrically closest across the *whole*
                        // screen when nothing qualifies nearby, and the fixed (non-scrolling) top bar
                        // apparently wins that comparison from deep inside the scrolling content in a way
                        // an off-screen sibling doesn't. scrollState.value stays large in that case (a
                        // genuine, deliberate walk back up through the content would have already scrolled
                        // back toward the top by the time focus reaches here) — that's what distinguishes
                        // it from someone actually meaning to reach the header. Bounces to the primary
                        // action button either way, since that is at least a real, expected landing spot
                        // instead of a random teleport.
                        .onFocusChanged { focusState ->
                            val stuckAtStartup = !userInteracted
                            val teleportedFromDeepContent = userInteracted && scrollState.value > 0
                            if (focusState.hasFocus) {
                                Log.d(
                                    "TvFocusDebug",
                                    "ExternalAppDetailScreen TopAppBar: hasFocus=true, userInteracted=" +
                                        "$userInteracted, scrollState=${scrollState.value} at " +
                                        "${System.currentTimeMillis()}" +
                                        if (stuckAtStartup || teleportedFromDeepContent) {
                                            " -> self-healing back to primary action"
                                        } else {
                                            ""
                                        },
                                )
                            }
                            if (focusState.hasFocus && (stuckAtStartup || teleportedFromDeepContent)) {
                                runCatching { primaryActionFocusRequester.requestFocus() }
                            }
                        }
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
        FloatingAppCardsBackground(Modifier.padding(contentPadding))
        if (app == null) {
            // The source was removed (or not loaded yet); nothing to show.
            Spacer(Modifier.padding(contentPadding))
            return@Scaffold
        }
        // Mirrors ExternalLifecycleActions' own installing check — whether the version list should show
        // progress on a row at all (combined with downloadTargetTag to know which one).
        val installing = installStates[appKey] == InstallState.Pending ||
            installStates[appKey] == InstallState.Installing
        val activeDownloadTag = downloadTargetTag[appKey]
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
                webUrl = app.webUrl,
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
        // catalogue card shows its installed version there. Also names where it actually came from when
        // known, same as the catalogue card — makes a copy installed by a different client obvious
        // instead of silently offering "Launch" with no explanation for why "Update" never appears.
        val footerText = installedVersion?.let { version ->
            val source = installSources[appKey]
            if (source != null) {
                stringResource(R.string.installed_version_source, version, source)
            } else {
                stringResource(R.string.external_installed_version, version)
            }
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
                isFavorite = app.key in favourites,
                onToggleFavorite = { viewModel.toggleFavourite(app) },
                // "App info" as a gear on the hero card itself, same as the F-Droid catalogue detail
                // screen — frees the action row below for the primary/uninstall buttons alone.
                onManageClick = if (isInstalled) {
                    app.packageName?.let { packageName -> { context.openAppInfo(packageName) } }
                } else {
                    null
                },
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
                        installedVersionName = installedVersion,
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
                        installedVersion = installedVersion,
                        onVersionClick = { versionToInstall = it },
                        onAnchorPositioned = { leftPaneVersionsAnchorY = it },
                        downloadStatus = downloads[appKey],
                        installing = installing,
                        activeDownloadTag = activeDownloadTag,
                        onCancel = { viewModel.cancel(app) },
                    )
                }
                VerticalDivider()
                Column(
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                        .onSizeChanged { viewportPx = it.height }
                        .verticalScroll(scrollState)
                        // So the last version card isn't glued to the bottom of the screen.
                        .padding(bottom = 24.dp),
                ) {
                    ExternalAppDetailBody(
                        app = app,
                        isInstalled = isInstalled,
                        installedVersion = installedVersion,
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
                        downloadStatus = downloads[appKey],
                        installing = installing,
                        activeDownloadTag = activeDownloadTag,
                        onCancel = { viewModel.cancel(app) },
                    )
                }
            }
        } else {
            // Android TV: the hero card (icon, name, stats, action buttons, "see all versions" link) is
            // guaranteed to already fit in the initial viewport, so navigating the D-pad within it should
            // never scroll the page — only leaving it, into the README below, should. Compose's own
            // "scroll the newly focused element into view" behaviour doesn't reliably respect that (it can
            // still nudge the scroll position even for an element that's already fully visible). A first
            // attempt corrected the drift after the fact (snapping back to 0 once it happened) but that
            // was visibly janky — the unwanted scroll still rendered a frame or two before the correction
            // landed. This suppresses it before it ever happens instead: a BringIntoViewSpec that's a
            // no-op while focus is still inside the card, and defers to the real (default) one everywhere
            // else, so scrolling the README into view still works normally once focus reaches it.
            //
            // The same reflex resurfaces further down the page too: moving focus between the short rows
            // right after the README (links, languages, the first few version rows) nudges scrollState
            // back by tens to ~100px on every single step, even though the position reached by paging
            // through the README already shows all of them — confirmed via real logcat. suppressBringIntoView
            // (set for a short window after every "down" press — see the screen root's own key handler)
            // covers that case the same way: no-op the reflex right when it would otherwise fire, rather
            // than fight the resulting scroll after the fact (confirmed janky the same way).
            var heroCardHasFocus by remember { mutableStateOf(true) }
            val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
            val bringIntoViewSpec = remember(isTelevision, defaultBringIntoViewSpec) {
                if (!isTelevision) {
                    defaultBringIntoViewSpec
                } else {
                    object : BringIntoViewSpec {
                        override fun calculateScrollDistance(
                            offset: Float,
                            size: Float,
                            containerSize: Float,
                        ): Float = if ((heroCardHasFocus && scrollState.value == 0) || suppressBringIntoView) {
                            // Only suppress the hero-card case while we're already sitting at the very
                            // top: that's the one case where the whole card is guaranteed to already fit.
                            // Coming back UP from the README with the card scrolled out of view still
                            // needs the normal behaviour, or focus moving further up within the card (say,
                            // from the "see all versions" link back toward the icon) would get stuck
                            // exactly where it re-entered instead of continuing to reveal the rest of the
                            // card above it.
                            0f
                        } else {
                            defaultBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize)
                        }
                    }
                }
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        // So the last version card isn't glued to the bottom of the screen.
                        .padding(bottom = 24.dp)
                        .onSizeChanged { viewportPx = it.height }
                        .verticalScroll(scrollState),
                ) {
                    Column(
                        modifier = if (isTelevision) {
                            Modifier.focusGroup().onFocusChanged {
                                if (it.hasFocus != heroCardHasFocus) {
                                    Log.d(
                                        "TvFocusDebug",
                                        "ExternalAppDetailScreen heroCardHasFocus: $heroCardHasFocus -> " +
                                            "${it.hasFocus}, scrollState=${scrollState.value} at " +
                                            "${System.currentTimeMillis()}",
                                    )
                                }
                                heroCardHasFocus = it.hasFocus
                            }
                        } else {
                            Modifier
                        },
                    ) {
                        headerCard()
                    }
                    ExternalAppDetailBody(
                        app = app,
                        isInstalled = isInstalled,
                        installedVersion = installedVersion,
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
                        downloadStatus = downloads[appKey],
                        installing = installing,
                        activeDownloadTag = activeDownloadTag,
                        onCancel = { viewModel.cancel(app) },
                    )
                }
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
    // The real on-device versionName (from the package manager), used to mark the version list's
    // genuinely-installed row — see ExternalVersionsSection's own doc comment for why this, not
    // app.installedTag, is the reliable source.
    installedVersion: String?,
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
    // Download/install progress, and which release it applies to — so the version list can show it on
    // the specific row the user tapped instead of only in the hero card. See ReleaseVersionItem.
    downloadStatus: DownloadStatus?,
    installing: Boolean,
    activeDownloadTag: String?,
    onCancel: () -> Unit,
) {
    val density = LocalDensity.current
    val uriHandler = LocalUriHandler.current
    // Keyed on app.key (stable for this screen), NOT on the html: the WebView captures its
    // height callback once, so the callback must keep targeting the same state instance. The
    // WebView re-reports its height every time the document reloads, so translating or reverting
    // resizes correctly on its own.
    var readmeHeightPx by remember(app.key) { mutableStateOf(0) }
    // True once the WebView's own renderer process has died (see ReadmeWebView's onRendererGone) — shows
    // a link to read the README on its own host instead of a permanently blank gap.
    var readmeRendererGone by remember(app.key) { mutableStateOf(false) }
    // TV: explicit hand-off target for the README's page-scroll once it reaches its bottom bound — see
    // tvPageScroll's own doc comment for why this is passed explicitly rather than left to Compose's
    // default focus search.
    val firstLinkFocusRequester = remember(app.key) { FocusRequester() }

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
    if (readmeHtml != null && !readmeRendererGone) {
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
        // A software-layered WebView (see ReadmeWebView's forceSoftwareLayer doc comment) can only ever
        // draw into a bitmap capped to roughly one screen's worth of pixels — confirmed on a real device
        // via logcat: "not displayed because it is too large to fit into a software layer", an
        // unrecoverable, fully blank render, not a partial one. A README taller than one viewport is
        // switched to the (small, historically emulator-only) hardware-rendering crash risk instead of a
        // *guaranteed* blank screen on real hardware for anything past a short README.
        val readmeNeedsHardwareLayer = viewportPx > 0 && readmeHeightPx > viewportPx
        // On TV this Box is the single focus stop for the whole README: landing here, the D-pad
        // pages the screen up/down through it (the WebView itself is non-focusable on TV, so the
        // remote no longer steps over its links and images). A plain wrapper on touch.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { readmeTopY = it.positionInParent().y.toInt() }
                .heightIn(max = if (readmeExpanded || !readmeCanCollapse) fullReadmeHeight else collapsedReadmeHeight)
                .clipToBounds()
                .tvPageScroll(
                    scrollState,
                    (viewportPx * 0.85f).toInt(),
                    debugLabel = "readme",
                    downTarget = if (showSidebarSections) firstLinkFocusRequester else null,
                ),
        ) {
            ReadmeWebView(
                html = readmeHtml,
                baseUrl = app.readmeWebBaseUrl,
                javaScriptEnabled = readmeJavaScriptEnabled,
                onContentHeight = {
                    if (it != readmeHeightPx) {
                        Log.d(
                            "TvFocusDebug",
                            "ExternalAppDetailScreen readmeHeightPx: $readmeHeightPx -> $it, " +
                                "scrollState=${scrollState.value}/${scrollState.maxValue} at " +
                                "${System.currentTimeMillis()}",
                        )
                    }
                    readmeHeightPx = it
                },
                scrollState = scrollState,
                forceSoftwareLayer = !readmeNeedsHardwareLayer,
                onRendererGone = { readmeRendererGone = true },
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
    } else if (readmeRendererGone) {
        // The WebView's renderer process died mid-render (see ReadmeWebView's onRenderProcessGone) —
        // rare, but leaving the space blank with no explanation or way forward would be worse than this.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.readme_render_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { uriHandler.openUri(app.webUrl) }) {
                Text(stringResource(R.string.source_code))
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
            firstRowFocusRequester = firstLinkFocusRequester,
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
            installedVersion = installedVersion,
            onVersionClick = onVersionClick,
            onAnchorPositioned = onAnchorPositioned,
            downloadStatus = downloadStatus,
            installing = installing,
            activeDownloadTag = activeDownloadTag,
            onCancel = onCancel,
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
    // TV: the README's page-scroll hands D-pad focus straight here once it reaches its bottom bound.
    // Null in the split-view left pane, where this section isn't right below a page-scrolling README.
    firstRowFocusRequester: FocusRequester? = null,
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
            focusRequester = firstRowFocusRequester,
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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExternalVersionsSection(
    app: ExternalApp,
    releaseHistory: List<Release>?,
    // The real on-device versionName (from the package manager) — NOT app.installedTag. That field is
    // only ever written once a tracked install genuinely reaches InstallState.Installed, but Android can
    // still report a false success on a silently-failed install (most commonly a signing-key conflict
    // with a copy installed by another client — see ExternalApp.hasUpdateGiven's own doc comment on this
    // exact class of bug), leaving installedTag pointing at a release that was never actually applied.
    // hasUpdateGiven already self-heals from that by falling back to this same live version whenever it
    // disagrees with the stored one; this row-level "installed" badge needs the identical fallback, or it
    // keeps confidently marking the wrong release "installed" even after the update banner (correctly,
    // via that same live check) says otherwise.
    installedVersion: String?,
    onVersionClick: (Release) -> Unit,
    onAnchorPositioned: (Int) -> Unit,
    downloadStatus: DownloadStatus?,
    installing: Boolean,
    activeDownloadTag: String?,
    onCancel: () -> Unit,
) {
    // Skip only once genuinely confirmed empty (releaseHistory != null). While still loading
    // (releaseHistory == null) the section stays in composition with a loading row below instead of
    // vanishing — on TV, tvPageScroll's D-pad paging (see the README Box above) releases focus once it
    // reaches whatever is currently the bottom of the scrollable content; if this whole section doesn't
    // exist yet because the release fetch is still in flight, that "bottom" is reached and D-pad focus
    // released *before* the real versions ever land, with nothing further down to land on. Keeping a
    // real (focusable) row here the whole time means the page's scrollable height already accounts for
    // this space from the first frame, and there's always something to page down to instead of the
    // remote silently hitting a dead end mid-load.
    if (releaseHistory != null && releaseHistory.isEmpty()) return
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
        if (releaseHistory == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .tvReadable(debugLabel = "versions-loading-row")
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                CircularWavyProgressIndicator(modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // Collapsed to the newest few by default — same as the F-Droid catalogue's version
            // list, so a long history doesn't turn the whole page into an endless scroll.
            var versionsExpanded by remember(app.key) { mutableStateOf(false) }
            val visibleCount = if (versionsExpanded) releaseHistory.size else VERSIONS_COLLAPSED_COUNT
            releaseHistory.take(visibleCount).forEach { release ->
                val isThisRowDownloading = activeDownloadTag == release.tag &&
                    (downloadStatus != null || installing)
                val apkName = release.apkFileName(filter = app.apkFilter)
                // See installedVersion's own doc comment: compares against the real live on-device
                // version, not the possibly-stale app.installedTag, the same self-healing fallback
                // hasUpdateGiven already relies on for the update banner above.
                val isInstalled = installedVersion != null &&
                    compareVersionStrings(apkVersionLabel(apkName ?: release.tag), installedVersion) == 0
                ReleaseVersionItem(
                    release = release,
                    apkName = apkName,
                    apkSize = release.apkFileSize(filter = app.apkFilter),
                    isSuggested = release.tag == app.latestTag,
                    isInstalled = isInstalled,
                    onClick = { onVersionClick(release) },
                    downloadStatus = if (isThisRowDownloading) downloadStatus else null,
                    installing = isThisRowDownloading && downloadStatus == null,
                    onCancel = if (isThisRowDownloading) onCancel else null,
                    modifier = Modifier.onFocusChanged {
                        if (it.isFocused) {
                            Log.d(
                                "TvFocusDebug",
                                "FOCUS -> external-version-row (${release.tag}) at " +
                                    "${System.currentTimeMillis()}",
                            )
                        }
                    },
                )
            }
            if (releaseHistory.size > VERSIONS_COLLAPSED_COUNT) {
                ShowMoreRow(
                    hiddenCount = releaseHistory.size - VERSIONS_COLLAPSED_COUNT,
                    expanded = versionsExpanded,
                    onToggle = { versionsExpanded = !versionsExpanded },
                    modifier = Modifier.onFocusChanged {
                        if (it.isFocused) {
                            Log.d(
                                "TvFocusDebug",
                                "FOCUS -> external-versions-show-more at ${System.currentTimeMillis()}",
                            )
                        }
                    },
                )
            }
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

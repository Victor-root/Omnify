package com.looker.droidify.compose.appDetail

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.looker.droidify.R
import com.looker.droidify.compose.appDetail.components.CustomButtonsRow
import com.looker.droidify.compose.appDetail.components.PackageItem
import com.looker.droidify.compose.appList.AppMinimalIcon
import com.looker.droidify.compose.components.BackButton
import com.looker.droidify.compose.components.CountBadge
import com.looker.droidify.compose.components.DescriptionTranslation
import com.looker.droidify.compose.components.DownloadProgressRow
import com.looker.droidify.compose.components.ExpandableText
import com.looker.droidify.compose.components.FloatingAppCardsBackground
import com.looker.droidify.compose.components.HeroCard
import com.looker.droidify.compose.components.HeroStatsRow
import com.looker.droidify.compose.components.InstallVersionDialog
import com.looker.droidify.compose.components.InstallingRow
import com.looker.droidify.compose.components.LinkRow
import com.looker.droidify.compose.components.premiumCardBorder
import com.looker.droidify.compose.components.RootBadge
import com.looker.droidify.compose.components.ScrollToTopFab
import com.looker.droidify.compose.components.SectionSeparator
import com.looker.droidify.compose.components.SectionTitle
import com.looker.droidify.compose.components.ShowMoreRow
import com.looker.droidify.compose.components.SplitViewToggleAction
import com.looker.droidify.compose.components.SupportedLanguages
import com.looker.droidify.compose.components.SupportedLanguagesSection
import com.looker.droidify.compose.components.TranslateAction
import com.looker.droidify.compose.components.heroFooter
import com.looker.droidify.compose.components.tvDpadDownTo
import com.looker.droidify.compose.components.tvDpadKeyLog
import com.looker.droidify.compose.components.tvFocusFill
import com.looker.droidify.compose.components.tvFocusOutline
import com.looker.droidify.compose.components.tvFocusScale
import com.looker.droidify.compose.components.tvReadable
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.data.model.App
import com.looker.droidify.data.model.minimal
import com.looker.droidify.data.model.FilePath
import com.looker.droidify.data.model.Package
import com.looker.droidify.data.model.Permission
import com.looker.droidify.data.model.Repo
import com.looker.droidify.data.model.selectForDevice
import com.looker.droidify.datastore.model.CustomButton
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.utility.common.RootDetection
import com.looker.droidify.utility.common.extension.openAppInfo
import com.looker.droidify.utility.common.shareUrl
import com.looker.droidify.utility.text.toAnnotatedString
import com.looker.droidify.compose.theme.AccentBarHeight
import com.looker.droidify.compose.theme.accentTopAppBarColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Maximum number of version rows rendered on the detail screen. The screen is a single
 * (non-lazy) scroll column, so every row composes eagerly; a handful of apps ship hundreds
 * of historical releases, which froze the UI. The list is sorted newest-first, so the cap
 * keeps the releases users actually care about.
 */
private const val MAX_VERSIONS_SHOWN = 50

/** Versions shown before the "show more" toggle, so an app with a long history doesn't turn the whole
 *  page into an endless scroll by default. */
private const val VERSIONS_COLLAPSED_COUNT = 5

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppDetailScreen(
    onBackClick: () -> Unit,
    viewModel: AppDetailViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val customButtons by viewModel.customButtons.collectAsStateWithLifecycle()
    val installState by viewModel.installState.collectAsStateWithLifecycle()
    val downloadStatus by viewModel.downloadStatus.collectAsStateWithLifecycle()
    val downloadTargetVersionCode by viewModel.downloadTargetVersionCode.collectAsStateWithLifecycle()
    val isFavourite by viewModel.isFavourite.collectAsStateWithLifecycle()
    val installedInfo by viewModel.installedInfo.collectAsStateWithLifecycle()
    val descriptionTranslation by viewModel.descriptionTranslation.collectAsStateWithLifecycle()
    val translationEnabled by viewModel.translationEnabled.collectAsStateWithLifecycle()
    val supportedLanguages by viewModel.supportedLanguages.collectAsStateWithLifecycle()
    val splitViewSettingEnabled by viewModel.splitViewEnabled.collectAsStateWithLifecycle()
    val successState = state as? AppDetailState.Success
    // The what's-new shown is the device-suitable release's text (falling back to the first package).
    // Translate the same text so the toggle covers the whole description area, not just summary + body.
    val suggestedWhatsNew = remember(successState) {
        successState?.let { s ->
            (
                s.packages.selectForDevice(s.app.metadata.suggestedVersionCode)?.first
                    ?: s.packages.firstOrNull()?.first
            )?.whatsNew
        }.orEmpty()
    }
    // Auto-translate on open when the setting is on (the ViewModel decides if it's actually needed).
    LaunchedEffect(successState?.app?.metadata?.packageName?.name) {
        successState?.app?.let {
            viewModel.maybeAutoTranslate(
                it.metadata.summary,
                it.metadata.description.raw,
                suggestedWhatsNew,
            )
        }
    }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val signatureConflict by viewModel.signatureConflict.collectAsStateWithLifecycle()
    // Hoisted above the Scaffold (not inside AppDetail) so both the content column and the
    // scroll-to-top FAB can read/drive the same scroll position.
    val scrollState = rememberScrollState()
    // TV D-pad diagnostics: logs every scroll-position change regardless of which focus transition (if
    // any) caused it — the tvFocus* modifiers' own logs only fire on a focus change, so a scroll jump
    // that happens WITHOUT one (e.g. a stray BringIntoView call, or something scrolling as a side effect
    // rather than a direct consequence of a logged focus move) would otherwise be invisible. Temporary,
    // see TvFocus.kt's own debug-logging doc comment.
    LaunchedEffect(Unit) {
        snapshotFlow { scrollState.value }.collect { value ->
            Log.d("TvFocusDebug", "AppDetailScreen scrollState -> $value at ${System.currentTimeMillis()}")
        }
    }

    // Play Store-style two-pane layout: only on a tablet-width screen in landscape (never on phones, TV,
    // or portrait), and only when the user hasn't turned the feature off entirely in Settings. A small
    // top-bar button (see splitViewManuallyOff below) additionally lets the user switch back to the
    // normal single-column layout without touching Settings, for this viewing only.
    val configuration = LocalConfiguration.current
    val splitViewAvailable = !LocalIsTelevision.current &&
        configuration.screenWidthDp >= 600 &&
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        splitViewSettingEnabled
    var splitViewManuallyOff by remember { mutableStateOf(false) }
    val useSplitView = splitViewAvailable && !splitViewManuallyOff

    // Re-read the installed state on resume — in particular when returning from the system uninstall
    // dialog — since installManager.state alone doesn't report a system uninstall. This is what lets the
    // post-downgrade auto-install fire once the old version is actually gone.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshInstalled()
    }

    signatureConflict?.let { conflict ->
        val conflictAppName = (state as? AppDetailState.Success)?.app?.metadata?.name
            ?: viewModel.packageName
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
                            viewModel.uninstall()
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

    // TV / D-pad: this requester points at the main action button (Install / Update / Launch). The
    // TopAppBar doesn't release focus downward on its own, so "down" on the back arrow would otherwise
    // leave the user stuck in the header; it now drops onto that button. No effect with touch. Also the
    // startup focus target: the earlier "opens already scrolled" bug that made focusing this directly
    // risky (its own relocation could overshoot while the page's layout was still settling) is now fixed
    // properly at the scroll level (see the BringIntoViewSpec below), so it no longer needs the
    // always-visible favourite heart as a workaround.
    val primaryActionFocusRequester = remember { FocusRequester() }
    val isTelevision = LocalIsTelevision.current
    // TV: whether the user has pressed any key on this screen yet. Once true, focus is entirely theirs —
    // nothing below may redirect it again. Set from the screen-root key handler (see the Scaffold
    // modifier below), which sees every key press regardless of what currently has focus.
    var userInteracted by remember { mutableStateOf(false) }
    // TV: open the detail with focus already on the primary action button instead of the back arrow,
    // retrying briefly until it's laid out. Keyed on the loaded app AND on installedInfo: the app's
    // metadata (successState) and its real installed state (installedInfo) resolve from two independent
    // flows, the latter via a package-manager read on a background dispatcher — it can genuinely land
    // after the metadata does, which flips PrimaryActions from Install to Launch and recomposes a brand
    // new button node the first retry burst never saw. Re-running the burst whenever installedInfo
    // changes catches that. No-op on touch.
    val successPackageName = successState?.app?.metadata?.packageName?.name
    if (isTelevision) {
        LaunchedEffect(successPackageName, installedInfo) {
            if (successPackageName != null) {
                repeat(20) {
                    val result = runCatching { primaryActionFocusRequester.requestFocus() }
                    Log.d(
                        "TvFocusDebug",
                        "AppDetailScreen startup retry #$it: primaryActionFocusRequester.requestFocus() " +
                            "success=${result.isSuccess} at ${System.currentTimeMillis()}",
                    )
                    if (result.isSuccess) return@LaunchedEffect
                    delay(50)
                }
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
                .tvDpadKeyLog("AppDetailScreen-root")
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        // Marks startup focus as "settled" — see userInteracted and the TopAppBar's own
                        // onFocusChanged below, which stop correcting focus the moment this is true.
                        if (!userInteracted) {
                            Log.d(
                                "TvFocusDebug",
                                "AppDetailScreen-root: userInteracted set true by ${event.key} at " +
                                    "${System.currentTimeMillis()}",
                            )
                        }
                        userInteracted = true
                    }
                    if (installedInfo != null && successPackageName != null &&
                        event.type == KeyEventType.KeyDown && event.key == Key.Menu
                    ) {
                        context.openAppInfo(successPackageName)
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
                modifier = Modifier
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            val result = runCatching { primaryActionFocusRequester.requestFocus() }
                            Log.d(
                                "TvFocusDebug",
                                "TopAppBar: DOWN bridges to primaryActionFocusRequester, success=" +
                                    "${result.isSuccess} at ${System.currentTimeMillis()}",
                            )
                            result.isSuccess
                        } else {
                            false
                        }
                    }
                    // TV: self-healing net for startup focus (userInteracted still false) AND for a
                    // second, distinct case confirmed by a real device log: pressing "up" or "left" deep
                    // in the scrolled content (well past the header, e.g. from the last version row, or
                    // from the README) can make Compose's own directional focus search jump straight to
                    // this top bar instead of the actually-adjacent element — its default 2D search picks
                    // whatever candidate is geometrically closest across the *whole* screen when nothing
                    // qualifies nearby, and the fixed (non-scrolling) top bar apparently wins that
                    // comparison from deep inside the scrolling content in a way an off-screen sibling
                    // doesn't. scrollState.value stays large in that case (a genuine, deliberate walk back
                    // up through the content would have already scrolled back toward the top by the time
                    // focus reaches here) — that's what distinguishes it from someone actually meaning to
                    // reach the header. Bounces to the primary action button either way, same as the
                    // startup case, since that is at least a real, expected landing spot instead of a
                    // random teleport.
                    .onFocusChanged { focusState ->
                        val stuckAtStartup = !userInteracted
                        val teleportedFromDeepContent = userInteracted && scrollState.value > 0
                        if (isTelevision && focusState.hasFocus) {
                            Log.d(
                                "TvFocusDebug",
                                "TopAppBar: hasFocus=true, userInteracted=$userInteracted, scrollState=" +
                                    "${scrollState.value} at ${System.currentTimeMillis()}" +
                                    if (stuckAtStartup || teleportedFromDeepContent) {
                                        " -> self-healing back to primary action"
                                    } else {
                                        ""
                                    },
                            )
                        }
                        if (isTelevision && focusState.hasFocus && (stuckAtStartup || teleportedFromDeepContent)) {
                            runCatching { primaryActionFocusRequester.requestFocus() }
                        }
                    },
                title = {
                    when (state) {
                        AppDetailState.Loading -> Text(stringResource(R.string.application))
                        is AppDetailState.Error -> {}
                        is AppDetailState.Success -> {
                            val app = (state as AppDetailState.Success).app
                            // Ellipsise so a long app name can't run under the translate action.
                            Text(
                                text = app.metadata.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton(onBackClick) },
                actions = {
                    if (splitViewAvailable) {
                        SplitViewToggleAction(
                            splitView = useSplitView,
                            onToggle = { splitViewManuallyOff = !splitViewManuallyOff },
                        )
                    }
                    // `successState?.let` (not a `successApp != null` check): a plain null check on the
                    // derived `successApp` doesn't smart-cast the separate `successState` variable, and
                    // the share action needs both.
                    successState?.let { success ->
                        IconButton(
                            onClick = {
                                val repo = success.packages
                                    .selectForDevice(success.app.metadata.suggestedVersionCode)?.second
                                    ?: success.packages.firstOrNull()?.second
                                if (repo != null) {
                                    shareApp(context, success.app.metadata.packageName.name, repo)
                                }
                            },
                            modifier = Modifier.tvFocusScale(debugLabel = "topbar-share"),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_share),
                                contentDescription = stringResource(R.string.share),
                            )
                        }
                    }
                    val successApp = successState?.app
                    if (translationEnabled && successApp != null &&
                        (successApp.metadata.description.isNotBlank() || suggestedWhatsNew.isNotBlank())
                    ) {
                        TranslateAction(
                            translation = descriptionTranslation,
                            onTranslate = {
                                viewModel.translateDescription(
                                    successApp.metadata.summary,
                                    successApp.metadata.description.raw,
                                    suggestedWhatsNew,
                                )
                            },
                            onShowOriginal = viewModel::showOriginalDescription,
                        )
                    }
                },
            )
        },
        floatingActionButton = { ScrollToTopFab(scrollState) },
    ) { padding ->
        FloatingAppCardsBackground(Modifier.padding(padding))
        when (state) {
            AppDetailState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularWavyProgressIndicator()
                }
            }

            is AppDetailState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = (state as AppDetailState.Error).message)
                }
            }

            is AppDetailState.Success -> {
                AppDetail(
                    app = (state as AppDetailState.Success).app,
                    packages = (state as AppDetailState.Success).packages,
                    customButtons = customButtons,
                    installState = installState,
                    downloadStatus = downloadStatus,
                    downloadTargetVersionCode = downloadTargetVersionCode,
                    installedInfo = installedInfo,
                    isFavourite = isFavourite,
                    onToggleFavourite = viewModel::toggleFavourite,
                    onSelectRepo = viewModel::setPreferredRepo,
                    onInstallOrUpdate = viewModel::installOrUpdate,
                    onInstallVersion = viewModel::installVersion,
                    onLaunch = viewModel::launch,
                    onUninstall = viewModel::uninstall,
                    onCancel = viewModel::cancel,
                    onCustomButtonClick = { url ->
                        try {
                            uriHandler.openUri(url)
                        } catch (_: Exception) {
                        }
                    },
                    descriptionTranslation = descriptionTranslation,
                    supportedLanguages = supportedLanguages,
                    primaryActionFocusRequester = primaryActionFocusRequester,
                    scrollState = scrollState,
                    useSplitView = useSplitView,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

/**
 * Shares a link to [packageName]'s own page via the system share sheet: F-Droid's or IzzyOnDroid's app
 * page when [repo] is (or mirrors) one of those, else a generic Omnify deep link — mirrors upstream
 * Droid-ify's share behaviour so the recipient lands on a real, working page either way.
 */
private fun shareApp(context: Context, packageName: String, repo: Repo) {
    val address = when {
        "https://f-droid.org/repo" in repo.mirrors ->
            "https://f-droid.org/packages/$packageName/"
        "https://f-droid.org/archive/repo" in repo.mirrors ->
            "https://f-droid.org/packages/$packageName/"
        "https://apt.izzysoft.de/fdroid/repo" in repo.mirrors ->
            "https://apt.izzysoft.de/fdroid/index/apk/$packageName"
        else -> shareUrl(packageName, repo.address)
    }
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_TEXT, address)
        type = "text/plain"
    }
    context.startActivity(Intent.createChooser(sendIntent, null))
}

@Composable
private fun PrimaryActions(
    packageName: String,
    isInstalled: Boolean,
    updateAvailable: Boolean,
    installState: InstallState?,
    downloadStatus: DownloadStatus?,
    onInstallOrUpdate: () -> Unit,
    onLaunch: () -> Unit,
    onUninstall: () -> Unit,
    onCancel: () -> Unit,
    primaryActionFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val installing = installState == InstallState.Pending || installState == InstallState.Installing
    val isTelevision = LocalIsTelevision.current
    // A phone-width button stretched to fill a tablet's much wider screen looked like an oversized
    // stray bar; capped to a comfortable reading width instead, matching the tablet breakpoint Material
    // itself uses (600dp) — phones (the vast majority of devices) are completely untouched.
    val isTablet = !isTelevision && LocalConfiguration.current.screenWidthDp >= 600
    // TV focus for the action buttons: the button simply scales up (no drawn ring, which floated off a
    // Material button's elevated, larger-than-visible bounds). On TV the buttons are big and centred (not
    // stretched full-width), small enough that the focus zoom stays on screen; touch keeps the full-width
    // buttons. The zoom is kept modest and the gap wide so a focused button doesn't grow into its
    // neighbour. A no-op on touch.
    when {
        downloadStatus != null -> DownloadProgressRow(
            status = downloadStatus,
            onCancel = onCancel,
            modifier = modifier.fillMaxWidth(),
            cancelFocusRequester = primaryActionFocusRequester,
        )

        installing -> InstallingRow(
            onCancel = onCancel,
            modifier = modifier.fillMaxWidth(),
            cancelFocusRequester = primaryActionFocusRequester,
        )

        else -> Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                if (isTelevision) 24.dp else 8.dp,
                if (isTelevision || isTablet) Alignment.CenterHorizontally else Alignment.Start,
            ),
        ) {
            // Big and centred on TV; capped to a reading width on tablet (see isTablet); full-width
            // (weight) on phones. The primary action also holds the startup focus (see
            // primaryActionFocusRequester).
            val tvPrimaryButton = when {
                isTelevision -> Modifier.height(60.dp).widthIn(min = 340.dp)
                isTablet -> Modifier.widthIn(min = 220.dp, max = 360.dp)
                else -> Modifier.weight(1f)
            }.focusRequester(primaryActionFocusRequester)
            val tvSecondaryButton = if (isTelevision) Modifier.height(60.dp).widthIn(min = 200.dp) else Modifier
            when {
                !isInstalled -> Button(
                    onClick = onInstallOrUpdate,
                    modifier = tvPrimaryButton.tvFocusScale(1.10f, debugLabel = "primary-install-button"),
                    // Slimmer than the default (which reserves generous side margins for text-only
                    // buttons): with an icon and a longer localised label (e.g. "Mettre à jour"), the
                    // default padding pushed the label onto two lines on a normal-width button.
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.install), maxLines = 1)
                }

                updateAvailable -> Button(
                    onClick = onInstallOrUpdate,
                    modifier = tvPrimaryButton.tvFocusScale(1.10f, debugLabel = "primary-update-button"),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.update), maxLines = 1)
                }

                else -> Button(
                    onClick = onLaunch,
                    modifier = tvPrimaryButton.tvFocusScale(1.10f, debugLabel = "primary-launch-button"),
                ) { Text(stringResource(R.string.launch)) }
            }
            if (isInstalled) {
                OutlinedButton(
                    onClick = onUninstall,
                    modifier = tvSecondaryButton.tvFocusScale(1.10f, debugLabel = "secondary-uninstall-button"),
                ) {
                    Text(stringResource(R.string.uninstall))
                }
                // "App info" now lives as a gear on the hero card itself (top-start, mirroring the
                // favourite heart) — see HeroCard's onManageClick — freeing this row for the primary/
                // uninstall buttons alone, since a long localised label (e.g. French "Mettre à jour")
                // was getting squeezed and truncated when a third button shared the row on phones.
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun AppDetail(
    app: App,
    packages: List<Pair<Package, Repo>>,
    customButtons: List<CustomButton>,
    installState: InstallState?,
    downloadStatus: DownloadStatus?,
    downloadTargetVersionCode: Long?,
    installedInfo: InstalledInfo?,
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    onSelectRepo: (Int) -> Unit,
    onInstallOrUpdate: () -> Unit,
    onInstallVersion: (Package, Repo) -> Unit,
    onLaunch: () -> Unit,
    onUninstall: () -> Unit,
    onCancel: () -> Unit,
    onCustomButtonClick: (url: String) -> Unit,
    descriptionTranslation: DescriptionTranslation,
    supportedLanguages: SupportedLanguages,
    primaryActionFocusRequester: FocusRequester,
    scrollState: ScrollState,
    useSplitView: Boolean,
    modifier: Modifier = Modifier,
) {
    // Not app.packages: that's only the single repo apps.first() picked as "the" app (see the ViewModel's
    // state builder), so when the installed version is offered by a *different* repo than that one (e.g.
    // installed from IzzyOnDroid while F-Droid's own build became the primary app), it wouldn't be found
    // and the button would wrongly offer "Install" even though a version is plainly marked installed
    // further down in the (correctly cross-repo) version list below. Search across every repo instead.
    val installedPackage = packages.map { it.first }.firstOrNull { it.installed }
    // Mirrors PrimaryActions' own installing check — whether the version list should show progress on
    // a row at all (combined with downloadTargetVersionCode to know which one).
    val installing = installState == InstallState.Pending || installState == InstallState.Installing
    // A version the user tapped in the list, awaiting confirmation to install (null = no dialog).
    var versionToInstall by remember { mutableStateOf<Pair<Package, Repo>?>(null) }
    // A downgrade the user confirmed: kept while the current app uninstalls, then installed automatically
    // once it's gone — so they don't have to tap the version again after the uninstall.
    var pendingDowngradeInstall by remember { mutableStateOf<Pair<Package, Repo>?>(null) }
    LaunchedEffect(installedInfo, pendingDowngradeInstall) {
        val pending = pendingDowngradeInstall ?: return@LaunchedEffect
        // installedInfo is re-read on every install/uninstall; null means the app is now gone.
        if (installedInfo == null) {
            onInstallVersion(pending.first, pending.second)
            pendingDowngradeInstall = null
        }
    }
    versionToInstall?.let { (pkg, repo) ->
        InstallVersionDialog(
            versionName = pkg.manifest.versionName,
            // A downgrade (older than what's installed) can't be applied in place — Android blocks it,
            // so the app must be uninstalled first.
            isDowngrade = installedPackage != null &&
                pkg.manifest.versionCode < installedPackage.manifest.versionCode,
            onInstall = {
                onInstallVersion(pkg, repo)
                versionToInstall = null
            },
            onUninstall = {
                // Remember what to install, uninstall the current version, and the effect above installs
                // it once the app is gone.
                pendingDowngradeInstall = pkg to repo
                onUninstall()
                versionToInstall = null
            },
            onDismiss = { versionToInstall = null },
        )
    }
    // The newest release this device can actually install (device-aware; see [selectForDevice]).
    // Comparing against the raw suggested code would keep flagging an update on, say, an x86 device,
    // because VLC's suggested code belongs to its (un-installable) arm64 build.
    val installable = remember(packages, app.metadata.suggestedVersionCode) {
        packages.selectForDevice(app.metadata.suggestedVersionCode)
    }
    val installablePackage = installable?.first
    val installableRepo = installable?.second
    val updateAvailable = installedPackage != null && installablePackage != null &&
        installedPackage.manifest.versionCode < installablePackage.manifest.versionCode
    val isTelevision = LocalIsTelevision.current
    val coroutineScope = rememberCoroutineScope()
    // Where the versions section actually lands once composed (in the scrolling Column's own
    // coordinate space), so the hero card's "see all versions" link can jump straight to it.
    var versionsAnchorY by remember { mutableStateOf(0) }
    // TV: "see all versions" only scrolls the page, it never moves focus — so the D-pad selection stays
    // on the (now off-screen) link, and pressing "up" immediately snaps the scroll straight back there
    // instead of stepping up through the version list one row at a time. Landing focus on the LAST
    // visible version row after the jump fixes that: from there "up" walks back through the list
    // normally, exactly as if the user had scrolled there themselves.
    val lastVersionFocusRequester = remember { FocusRequester() }
    // Split view only: the left pane (hero card + links + versions) scrolls on its own, separately from
    // the right pane, so a long version list there doesn't overflow the pane uncontrolled. Its own
    // "see all versions" anchor, parallel to versionsAnchorY above.
    val leftPaneScrollState = rememberScrollState()
    var leftPaneVersionsAnchorY by remember { mutableStateOf(0) }
    // The visible height of whichever column the description sits in, so the description can collapse
    // to fill exactly the space left below it down to the bottom of the screen (see AppDetailBody).
    var viewportPx by remember { mutableStateOf(0) }
    // The hero card content is identical in both layouts below (nothing moved out of it) — kept as one
    // lambda so the single-column and split-view branches can never drift apart on it.
    val headerCard: @Composable () -> Unit = {
        AppHeaderCard(
            app = app,
            packageName = app.metadata.packageName.name,
            installedPackage = installedPackage,
            installablePackage = installablePackage,
            installedInfo = installedInfo,
            isInstalled = installedPackage != null,
            isFavorite = isFavourite,
            onToggleFavorite = onToggleFavourite,
            updateAvailable = updateAvailable,
            installState = installState,
            downloadStatus = downloadStatus,
            onInstallOrUpdate = onInstallOrUpdate,
            onLaunch = onLaunch,
            onUninstall = onUninstall,
            onCancel = onCancel,
            primaryActionFocusRequester = primaryActionFocusRequester,
            onViewVersionsClick = if (packages.isNotEmpty()) {
                {
                    coroutineScope.launch {
                        if (useSplitView) {
                            leftPaneScrollState.animateScrollTo(leftPaneVersionsAnchorY)
                        } else {
                            scrollState.animateScrollTo(versionsAnchorY)
                            if (isTelevision) {
                                repeat(20) {
                                    if (runCatching { lastVersionFocusRequester.requestFocus() }.isSuccess) {
                                        return@launch
                                    }
                                    delay(50)
                                }
                            }
                        }
                    }
                }
            } else {
                null
            },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    // Shared with AppDetailBody's own copy of this lookup: cheap, and lets the left pane below render
    // permissions without threading a second parameter through just for this.
    val suggestedPackage = installablePackage ?: packages.firstOrNull()?.first
    val permissions = remember(suggestedPackage) { suggestedPackage?.manifest?.permissions.orEmpty() }
    if (useSplitView) {
        // Tablet landscape only (see useSplitView): a Play Store-style two-pane layout. The hero card,
        // links, permissions, supported languages and versions sit in the left pane (scrolling on its
        // own if that content runs long), while everything else scrolls independently in the right pane.
        Row(modifier = Modifier.fillMaxSize().then(modifier)) {
            Column(
                modifier = Modifier
                    .weight(0.4f)
                    .fillMaxHeight()
                    .verticalScroll(leftPaneScrollState)
                    .padding(top = 16.dp),
            ) {
                headerCard()
                if (app.hasLinks()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinksSection(app = app)
                }
                if (permissions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    PermissionsSection(permissions = permissions)
                }
                if (supportedLanguages.codes.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SupportedLanguagesSection(languages = supportedLanguages)
                }
                if (packages.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    SectionSeparator()
                    Spacer(modifier = Modifier.height(16.dp))
                    VersionsSection(
                        packages = packages,
                        installableRepo = installableRepo,
                        onSelectRepo = onSelectRepo,
                        app = app,
                        onVersionClick = { pkg, repo -> versionToInstall = pkg to repo },
                        onAnchorPositioned = { leftPaneVersionsAnchorY = it },
                        downloadStatus = downloadStatus,
                        installing = installing,
                        downloadTargetVersionCode = downloadTargetVersionCode,
                        onCancel = onCancel,
                    )
                }
            }
            VerticalDivider()
            Column(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxHeight()
                    .onSizeChanged { viewportPx = it.height }
                    .verticalScroll(scrollState)
                    .focusGroup()
                    .padding(top = 16.dp, bottom = 24.dp),
            ) {
                AppDetailBody(
                    app = app,
                    installablePackage = installablePackage,
                    packages = packages,
                    customButtons = customButtons,
                    onCustomButtonClick = onCustomButtonClick,
                    descriptionTranslation = descriptionTranslation,
                    supportedLanguages = supportedLanguages,
                    installableRepo = installableRepo,
                    onSelectRepo = onSelectRepo,
                    onVersionClick = { pkg, repo -> versionToInstall = pkg to repo },
                    onAnchorPositioned = { versionsAnchorY = it },
                    viewportPx = viewportPx,
                    showSidebarSections = false,
                    downloadStatus = downloadStatus,
                    installing = installing,
                    downloadTargetVersionCode = downloadTargetVersionCode,
                    onCancel = onCancel,
                )
            }
        }
    } else {
        // Android TV: the hero card (icon, name, stats, action buttons, "see all versions" link) is
        // guaranteed to already fit in the initial viewport, so navigating the D-pad within it should
        // never scroll the page — only leaving it, into the description/README below, should. Compose's
        // own "scroll the newly focused element into view" behaviour doesn't reliably respect that (it
        // can still nudge the scroll position even for an element that's already fully visible). A first
        // attempt corrected the drift after the fact (snapping back to 0 once it happened) but that was
        // visibly janky — the unwanted scroll still rendered a frame or two before the correction landed.
        // This suppresses it before it ever happens instead: a BringIntoViewSpec that's a no-op while
        // focus is still inside the card, and defers to the real (default) one everywhere else, so
        // scrolling the description/README into view still works normally once focus reaches it.
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
                    ): Float = if (heroCardHasFocus && scrollState.value == 0) {
                        // Only suppress while we're already sitting at the very top: that's the one case
                        // where the whole card is guaranteed to already fit. Coming back UP from the
                        // description with the card scrolled out of view still needs the normal
                        // behaviour, or focus moving further up within the card (say, from the "see all
                        // versions" link back toward the icon) would get stuck exactly where it re-entered
                        // instead of continuing to reveal the rest of the card above it.
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
                    .onSizeChanged { viewportPx = it.height }
                    .verticalScroll(scrollState)
                    // A focus group so D-pad navigation stays scoped to the content (the action button itself is
                    // the explicit focus target, see primaryActionFocusRequester).
                    .focusGroup()
                    .then(modifier)
                    // Breathing room so the header section isn't glued under the top bar, and the
                    // last version card isn't glued to the bottom of the screen either.
                    .padding(top = 16.dp, bottom = 24.dp),
            ) {
                Column(
                    modifier = if (isTelevision) {
                        Modifier.focusGroup().onFocusChanged {
                            if (it.hasFocus != heroCardHasFocus) {
                                Log.d(
                                    "TvFocusDebug",
                                    "heroCardHasFocus: $heroCardHasFocus -> ${it.hasFocus}, " +
                                        "scrollState=${scrollState.value} at ${System.currentTimeMillis()}",
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
                AppDetailBody(
                    app = app,
                    installablePackage = installablePackage,
                    packages = packages,
                    customButtons = customButtons,
                    onCustomButtonClick = onCustomButtonClick,
                    descriptionTranslation = descriptionTranslation,
                    supportedLanguages = supportedLanguages,
                    installableRepo = installableRepo,
                    onSelectRepo = onSelectRepo,
                    onVersionClick = { pkg, repo -> versionToInstall = pkg to repo },
                    onAnchorPositioned = { versionsAnchorY = it },
                    viewportPx = viewportPx,
                    showSidebarSections = true,
                    downloadStatus = downloadStatus,
                    installing = installing,
                    downloadTargetVersionCode = downloadTargetVersionCode,
                    onCancel = onCancel,
                    lastVersionFocusRequester = lastVersionFocusRequester,
                )
            }
        }
    }
}

/**
 * Everything on the detail screen after the hero card: the Google-services notice, custom buttons,
 * screenshots, categories, summary/description, what's-new, links, anti-features, permissions,
 * supported languages, and the version list. Extracted so the single-column and tablet-landscape
 * two-pane layouts in [AppDetail] call the exact same content instead of two copies that could drift
 * apart. Deliberately emits its composables directly with no wrapping Column of its own, so they land
 * as direct children of whichever scrolling Column calls it — keeping the version list's scroll anchor
 * (see [onAnchorPositioned]) correct in both layouts.
 */
@Composable
private fun AppDetailBody(
    app: App,
    installablePackage: Package?,
    packages: List<Pair<Package, Repo>>,
    customButtons: List<CustomButton>,
    onCustomButtonClick: (url: String) -> Unit,
    descriptionTranslation: DescriptionTranslation,
    supportedLanguages: SupportedLanguages,
    installableRepo: Repo?,
    onSelectRepo: (Int) -> Unit,
    onVersionClick: (Package, Repo) -> Unit,
    onAnchorPositioned: (Int) -> Unit,
    // The height of the scrolling column this body sits in, so the description can collapse to fill
    // exactly the space left below it down to the bottom of the screen.
    viewportPx: Int,
    // False in split view: the tablet-landscape left pane shows links, permissions, supported languages
    // and versions itself (see AppDetail), so this body must not repeat them.
    showSidebarSections: Boolean,
    // Download/install progress, and which version it applies to — so the version list can show it on
    // the specific row the user tapped instead of only in the hero card. See PackageItem.
    downloadStatus: DownloadStatus?,
    installing: Boolean,
    downloadTargetVersionCode: Long?,
    onCancel: () -> Unit,
    // TV only: focus target for the hero card's "see all versions" link, attached to the last visible
    // version row (see VersionsSection). Null in split view, where the versions list lives in the left
    // pane's own direct VersionsSection call instead of through this body.
    lastVersionFocusRequester: FocusRequester? = null,
) {
    // Summary + description come first, right after the hero card, before anything else.
    Spacer(modifier = Modifier.height(8.dp))
    if (app.metadata.summary.isNotBlank()) {
        // The bold summary is translated together with the description, so it switches too.
        val shownSummary = (descriptionTranslation as? DescriptionTranslation.Translated)
            ?.summary?.takeIf { it.isNotBlank() } ?: app.metadata.summary
        Text(
            text = shownSummary,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    if (app.metadata.description.isNotBlank()) {
        if (app.metadata.summary.isNotBlank()) Spacer(modifier = Modifier.height(4.dp))
        val handler = LocalUriHandler.current
        // Parsing the HTML description is expensive; do it once per description instead of on
        // every recomposition (the detail screen recomposes repeatedly while data loads).
        val description = remember(app.metadata.description) {
            app.metadata.description.toAnnotatedString(onUrlClick = { handler.openUri(it) })
        }
        // Show the translation when one is ready (the toggle lives in the top bar); otherwise the
        // original formatted text.
        val translated = descriptionTranslation as? DescriptionTranslation.Translated
        val shownDescription = if (translated != null && translated.description.isNotBlank()) {
            AnnotatedString(translated.description)
        } else {
            description
        }
        // How much room is left below the description down to the bottom of the visible screen, so it
        // can collapse to fill exactly that instead of an arbitrary fixed line count.
        var descriptionTopY by remember { mutableStateOf(0) }
        // Collapsed with a real "Show more" button for long descriptions, instead of always rendering
        // the whole thing (some descriptions run for dozens of lines).
        ExpandableText(
            text = shownDescription,
            style = MaterialTheme.typography.bodyMedium,
            availableHeightPx = if (viewportPx > 0 && descriptionTopY > 0) {
                (viewportPx - descriptionTopY).coerceAtLeast(0)
            } else {
                null
            },
            // Split view: the right pane is dedicated space for this body content, so the description
            // is never worth collapsing there (showSidebarSections is false only in that pane).
            alwaysExpanded = !showSidebarSections,
            // TV: a D-pad focus stop so the remote can land on the description and scroll it into
            // view instead of jumping over it to the buttons below. No-op on touch.
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .onGloballyPositioned { descriptionTopY = it.positionInParent().y.toInt() }
                .tvReadable(debugLabel = "description"),
        )
    }

    // Detect exactly which Google Play services capabilities this build depends on (push, maps,
    // billing, ...) from its manifest, so we can say precisely what may not work on a de-Googled
    // device and whether microG covers it. Package-name aware, so a services *provider* like microG
    // (com.google.android.gms) never warns about needing itself.
    val gmsPackage = installablePackage ?: packages.firstOrNull()?.first
    val gmsDependencies = remember(gmsPackage, app.metadata.packageName.name) {
        if (gmsPackage == null) {
            emptyList()
        } else {
            detectGoogleServicesDependencies(
                packageName = app.metadata.packageName.name,
                permissionNames = gmsPackage.manifest.permissions.mapTo(HashSet()) { it.name },
                featureNames = gmsPackage.features.toHashSet(),
            )
        }
    }
    if (gmsDependencies.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        GoogleServicesCard(
            dependencies = gmsDependencies,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    if (customButtons.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        CustomButtonsRow(
            buttons = customButtons,
            packageName = app.metadata.packageName.name,
            appName = app.metadata.name,
            authorName = app.author?.name,
            onButtonClick = onCustomButtonClick,
        )
    }

    val screenshots: List<FilePath> = remember(app.screenshots) {
        buildList {
            app.screenshots?.phone?.let { addAll(it) }
            app.screenshots?.sevenInch?.let { addAll(it) }
            app.screenshots?.tenInch?.let { addAll(it) }
            app.screenshots?.tv?.let { addAll(it) }
            app.screenshots?.wear?.let { addAll(it) }
        }
    }
    if (screenshots.isNotEmpty()) {
        Spacer(modifier = Modifier.height(16.dp))
        ScreenshotsRow(screenshots = screenshots)
    }

    if (app.categories.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        CategoriesRow(categories = app.categories)
    }

    val suggestedPackage = installablePackage ?: packages.firstOrNull()?.first

    suggestedPackage?.whatsNew?.takeIf { it.isNotBlank() }?.let { whatsNew ->
        Spacer(modifier = Modifier.height(16.dp))
        val translatedWhatsNew = (descriptionTranslation as? DescriptionTranslation.Translated)
            ?.whatsNew?.takeIf { it.isNotBlank() }
        WhatsNewSection(whatsNew = translatedWhatsNew ?: whatsNew)
    }

    if (showSidebarSections && app.hasLinks()) {
        Spacer(modifier = Modifier.height(8.dp))
        LinksSection(app = app)
    }

    val antiFeatures = suggestedPackage?.antiFeatures.orEmpty()
    if (antiFeatures.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        AntiFeaturesSection(antiFeatures = antiFeatures)
    }

    val permissions = suggestedPackage?.manifest?.permissions.orEmpty()
    if (showSidebarSections && permissions.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        PermissionsSection(permissions = permissions)
    }

    if (showSidebarSections && supportedLanguages.codes.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        SupportedLanguagesSection(languages = supportedLanguages)
    }

    if (showSidebarSections) {
        Spacer(modifier = Modifier.height(16.dp))

        VersionsSection(
            packages = packages,
            installableRepo = installableRepo,
            onSelectRepo = onSelectRepo,
            app = app,
            onVersionClick = onVersionClick,
            onAnchorPositioned = onAnchorPositioned,
            downloadStatus = downloadStatus,
            installing = installing,
            downloadTargetVersionCode = downloadTargetVersionCode,
            onCancel = onCancel,
            lastVersionFocusRequester = lastVersionFocusRequester,
        )
    }
}

/**
 * The repo tabs (when more than one offers the app) and the version list — extracted only so
 * [AppDetail] can wrap it in a single [onGloballyPositioned] anchor for the hero card's "see all
 * versions" link; the content is unchanged.
 */
@Composable
private fun VersionsSection(
    packages: List<Pair<Package, Repo>>,
    installableRepo: Repo?,
    onSelectRepo: (Int) -> Unit,
    app: App,
    onVersionClick: (Package, Repo) -> Unit,
    onAnchorPositioned: (Int) -> Unit,
    downloadStatus: DownloadStatus?,
    installing: Boolean,
    downloadTargetVersionCode: Long?,
    onCancel: () -> Unit,
    lastVersionFocusRequester: FocusRequester? = null,
) {
    Column(modifier = Modifier.onGloballyPositioned { onAnchorPositioned(it.positionInParent().y.toInt()) }) {
        SectionTitle(stringResource(R.string.versions))
        // Repos that offer this app. When more than one offers it (e.g. F-Droid + IzzyOnDroid), show
        // tabs so the user can pick which repo to install from; both the version list below and the
        // Install/Update button then follow the selected repo. Default to the repo that provides the
        // device-suggested version.
        val repos = remember(packages) { packages.map { it.second }.distinctBy { it.id } }
        var selectedRepoId by remember(repos, installableRepo) {
            mutableStateOf(installableRepo?.id ?: repos.firstOrNull()?.id)
        }
        LaunchedEffect(selectedRepoId) { selectedRepoId?.let(onSelectRepo) }
        // TV / D-pad: Material3's ScrollableTabRow doesn't release focus downward on its own, leaving a
        // remote user stuck once they've picked a repo tab. Points at the first version row below. No
        // effect on touch.
        val firstVersionFocusRequester = remember { FocusRequester() }
        if (repos.size > 1) {
            val selectedIndex = repos.indexOfFirst { it.id == selectedRepoId }.coerceAtLeast(0)
            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                edgePadding = 16.dp,
                modifier = Modifier.fillMaxWidth().tvDpadDownTo(firstVersionFocusRequester, debugLabel = "repo-tabs"),
            ) {
                repos.forEachIndexed { index, repo ->
                    Tab(
                        selected = index == selectedIndex,
                        onClick = { selectedRepoId = repo.id },
                        text = { Text(repo.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Versions offered by the selected repo. Multi-ABI apps (Brave, VLC, …) ship one APK per
        // architecture under the SAME versionName but DIFFERENT version codes, so the raw list showed
        // the same "version" several times — including builds this device can't even install. Collapse
        // to one row per versionName, keeping the build the device can actually install (primary ABI
        // first); versions with no compatible build are dropped. This screen is a single (non-lazy)
        // scroll column, so every row composes eagerly; cap to the newest releases (sorted below).
        val shownPackages = remember(packages, selectedRepoId) {
            packages
                .filter { it.second.id == selectedRepoId }
                .groupBy { it.first.manifest.versionName }
                .values
                .mapNotNull { variants -> variants.selectForDevice(Long.MAX_VALUE) }
                .sortedByDescending { it.first.manifest.versionCode }
        }
        // The release the Install button would pull from the selected repo gets the "suggested" badge,
        // so the badge always matches what tapping Install actually does for this repo.
        val suggestedVersion = remember(shownPackages, app.metadata.suggestedVersionCode) {
            shownPackages.selectForDevice(app.metadata.suggestedVersionCode)?.first?.manifest?.versionCode
        }
        // Collapsed to the newest few by default — an app with a long version history shouldn't make the
        // whole page an endless scroll. Collapses back on switching repo tabs, since that's a new list.
        var versionsExpanded by remember(selectedRepoId) { mutableStateOf(false) }
        val visibleCount = if (versionsExpanded) MAX_VERSIONS_SHOWN else VERSIONS_COLLAPSED_COUNT
        // Where "see all versions" (see the hero card link) lands focus, so D-pad "up" from there walks
        // back up through the list normally instead of jumping straight back to that now off-screen link.
        val lastVisibleIndex = minOf(visibleCount, shownPackages.size) - 1
        shownPackages.take(visibleCount).forEachIndexed { index, (pkg, repo) ->
            val isSuggested = suggestedVersion != null && pkg.manifest.versionCode == suggestedVersion
            val isThisRowDownloading = downloadTargetVersionCode == pkg.manifest.versionCode &&
                (downloadStatus != null || installing)
            PackageItem(
                item = pkg,
                repo = repo,
                onClick = { onVersionClick(pkg, repo) },
                onLongClick = {},
                modifier = Modifier
                    .then(if (index == 0) Modifier.focusRequester(firstVersionFocusRequester) else Modifier)
                    .then(
                        if (index == lastVisibleIndex && lastVersionFocusRequester != null) {
                            Modifier.focusRequester(lastVersionFocusRequester)
                        } else {
                            Modifier
                        },
                    )
                    .onFocusChanged {
                        if (it.isFocused) {
                            Log.d(
                                "TvFocusDebug",
                                "FOCUS -> version-row[$index] (${pkg.manifest.versionName}) at " +
                                    "${System.currentTimeMillis()}",
                            )
                        }
                    },
                highlighted = isSuggested,
                downloadStatus = if (isThisRowDownloading) downloadStatus else null,
                installing = isThisRowDownloading && downloadStatus == null,
                onCancel = if (isThisRowDownloading) onCancel else null,
            ) {
                // Both chips can apply at once (the installed version is also the suggested one), so show
                // them side by side instead of one excluding the other.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isSuggested) {
                        Text(
                            text = stringResource(R.string.suggested).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = CircleShape,
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                    if (pkg.installed) {
                        Text(
                            text = stringResource(R.string.installed).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    shape = CircleShape,
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
        if (shownPackages.size > VERSIONS_COLLAPSED_COUNT) {
            ShowMoreRow(
                hiddenCount = minOf(shownPackages.size, MAX_VERSIONS_SHOWN) - VERSIONS_COLLAPSED_COUNT,
                expanded = versionsExpanded,
                onToggle = { versionsExpanded = !versionsExpanded },
            )
        }
    }
}

/**
 * The app's data resolved into the shared [HeroCard] shell: icon, name, author, a favourite toggle, a
 * version/size/source-code stats row, and the primary install/update/launch action — the F-Droid
 * catalogue's flavour of the same card the external-source detail screen uses, so the two read as the
 * same app page rather than two different designs.
 */
@Composable
private fun AppHeaderCard(
    app: App?,
    packageName: String,
    installedPackage: Package?,
    installablePackage: Package?,
    installedInfo: InstalledInfo?,
    isInstalled: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    updateAvailable: Boolean,
    installState: InstallState?,
    downloadStatus: DownloadStatus?,
    onInstallOrUpdate: () -> Unit,
    onLaunch: () -> Unit,
    onUninstall: () -> Unit,
    onCancel: () -> Unit,
    primaryActionFocusRequester: FocusRequester,
    onViewVersionsClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Reuse the list tile's icon logic so the header falls back the same way: repo icon, then the
    // repo's generic icon.png, then the installed app's own launcher icon, then a placeholder. Repos
    // like TwinHelix ship no icon in their index, so without the launcher-icon fallback the header
    // showed a blank placeholder even though the list (which has this fallback) showed the real logo.
    val minimal = app?.minimal()
    val version = app?.metadata?.suggestedVersionName?.nonBlank()
    val size = installablePackage?.apk?.size?.toString()
    val heroContext = LocalContext.current
    val author = app?.author?.name?.nonBlank()
    val uriHandler = LocalUriHandler.current
    val sourceCodeUrl = app?.links?.sourceCode?.nonBlank()
    val onSourceCodeClick: (() -> Unit)? = sourceCodeUrl?.let { url ->
        { runCatching { uriHandler.openUri(url) } }
    }
    // Fuzzy but shared with the Discover home's "For rooted devices" carousel (see RootDetection): the
    // legacy superuser permission, or strong root phrasing (Magisk/KernelSU/"requires root"…) in the
    // app's own name/summary/description, minus an explicit negation ("works without root"…).
    val isRootCompatible = remember(app, installablePackage, installedPackage) {
        app != null && (
            RootDetection.textIndicatesRoot(
                "${app.metadata.name} ${app.metadata.summary} ${app.metadata.description.raw}",
            ) ||
                (installablePackage ?: installedPackage)?.manifest?.permissions
                    ?.any { it.name == RootDetection.PERMISSION } == true
            )
    }

    HeroCard(
        modifier = modifier,
        icon = {
            if (minimal != null) {
                AppMinimalIcon(
                    app = minimal,
                    isInstalled = isInstalled,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(20.dp)),
                )
            } else {
                Image(
                    painter = painterResource(id = R.drawable.ic_cannot_load),
                    contentDescription = null,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(20.dp)),
                )
            }
        },
        name = app?.metadata?.name ?: packageName,
        subtitle = author?.let { stringResource(R.string.by_author_FORMAT, it) },
        isFavorite = isFavorite,
        onToggleFavorite = onToggleFavorite,
        onManageClick = if (isInstalled) {
            { heroContext.openAppInfo(packageName) }
        } else {
            null
        },
        badge = if (isRootCompatible) { { RootBadge() } } else null,
        stats = if (version != null || size != null || onSourceCodeClick != null) {
            { HeroStatsRow(version = version, size = size, onSourceCodeClick = onSourceCodeClick) }
        } else {
            null
        },
        actions = {
            PrimaryActions(
                packageName = packageName,
                isInstalled = isInstalled,
                updateAvailable = updateAvailable,
                installState = installState,
                downloadStatus = downloadStatus,
                onInstallOrUpdate = onInstallOrUpdate,
                onLaunch = onLaunch,
                onUninstall = onUninstall,
                onCancel = onCancel,
                primaryActionFocusRequester = primaryActionFocusRequester,
            )
        },
        // The real installed version + its source (e.g. a fork installed over the upstream package
        // keeps its own version), folded into the card instead of sitting orphaned below it.
        footer = heroFooter(
            infoText = installedInfo?.let { info ->
                stringResource(R.string.installed_version_source, info.version, info.source)
            },
            onViewVersionsClick = onViewVersionsClick,
        ),
    )
}

@Composable
private fun WhatsNewSection(whatsNew: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.whats_new))
        Text(
            text = whatsNew,
            style = MaterialTheme.typography.bodyMedium,
            // TV: a D-pad focus stop so the remote can land here and scroll it into view. No-op on touch.
            modifier = Modifier.padding(horizontal = 16.dp).tvReadable(debugLabel = "whats-new"),
        )
    }
}

@Composable
private fun LinksSection(app: App) {
    val uriHandler = LocalUriHandler.current
    val open: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (_: Exception) {
        }
    }
    val links = app.links
    val author = app.author
    val donateUrl = app.donation?.regularUrl?.firstOrNull()
    Column(modifier = Modifier.fillMaxWidth()) {
        links?.webSite?.nonBlank()?.let { url ->
            LinkRow(R.drawable.ic_public, stringResource(R.string.website), url) { open(url) }
        }
        links?.sourceCode?.nonBlank()?.let { url ->
            LinkRow(R.drawable.ic_source_code, stringResource(R.string.source_code), url) { open(url) }
        }
        links?.issueTracker?.nonBlank()?.let { url ->
            LinkRow(R.drawable.ic_bug_report, stringResource(R.string.issue_tracker), url) { open(url) }
        }
        links?.changelog?.nonBlank()?.let { url ->
            LinkRow(R.drawable.ic_history, stringResource(R.string.changelog), url) { open(url) }
        }
        links?.translation?.nonBlank()?.let { url ->
            LinkRow(R.drawable.ic_language, stringResource(R.string.translation), url) { open(url) }
        }
        author?.web?.nonBlank()?.let { url ->
            LinkRow(
                R.drawable.ic_person,
                author?.name?.nonBlank() ?: stringResource(R.string.author_website),
                url,
            ) { open(url) }
        }
        donateUrl?.nonBlank()?.let { url ->
            LinkRow(R.drawable.ic_donate, stringResource(R.string.donate), url) { open(url) }
        }
    }
}


/**
 * Detailed notice that the app depends on specific Google Play services, shown before install so it's
 * clear what may not work on a de-Googled device and whether microG covers it.
 *
 * Two tiers, driven by the detected [dependencies]: if any capability is entirely uncovered by microG
 * (a "hard" gap like billing or Maps v1) the header is firmer; otherwise it's a soft, informative note.
 * Below the header, one row per detected capability lists what it's for and a microG-coverage chip, so
 * the user knows precisely whether installing microG would fix it.
 */
@Composable
private fun GoogleServicesCard(
    dependencies: List<GoogleServiceDependency>,
    modifier: Modifier = Modifier,
) {
    val hasHardGap = dependencies.any { it.coverage == MicrogCoverage.NONE }
    val shape = MaterialTheme.shapes.medium
    // See the doc comment on premiumCardBorder's HeroCard usage: the border must live on this
    // outer Box, not inside Surface's own modifier, or its own background paints over it.
    Box(modifier = modifier.fillMaxWidth().then(premiumCardBorder(shape))) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            // White instead of a flat grey card — the gradient border above is what ties it to
            // the theme, not a flat colour fill.
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shape,
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_tabler_brand_google),
                        contentDescription = null,
                        tint = if (hasHardGap) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(22.dp),
                    )
                    Column {
                        Text(
                            text = stringResource(
                                if (hasHardGap) {
                                    R.string.google_services_hard_title
                                } else {
                                    R.string.google_services_soft_title
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(
                                if (hasHardGap) {
                                    R.string.google_services_hard_summary
                                } else {
                                    R.string.google_services_soft_summary
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                dependencies.forEach { dependency ->
                    GoogleServiceDependencyRow(dependency)
                }
            }
        }
    }
}

/** One detected capability: its name, what breaks without it, and a microG-coverage chip. */
@Composable
private fun GoogleServiceDependencyRow(dependency: GoogleServiceDependency) {
    Spacer(modifier = Modifier.height(12.dp))
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(dependency.labelRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            MicrogCoverageChip(dependency.coverage)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(dependency.descriptionRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Small pill telling the user whether microG restores this capability. */
@Composable
private fun MicrogCoverageChip(coverage: MicrogCoverage) {
    val (labelRes, container, content) = when (coverage) {
        MicrogCoverage.FULL -> Triple(
            R.string.google_services_microg_full,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        MicrogCoverage.PARTIAL -> Triple(
            R.string.google_services_microg_partial,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        MicrogCoverage.NONE -> Triple(
            R.string.google_services_microg_none,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun AntiFeaturesSection(antiFeatures: List<String>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.anti_features), R.drawable.ic_tabler_alert_triangle)
        antiFeatures.forEach { tag ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = antiFeatureLabel(tag),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Localised description for an F-Droid anti-feature tag; unknown tags fall back to the raw tag. */
@Composable
private fun antiFeatureLabel(tag: String): String = when (tag) {
    "Ads" -> stringResource(R.string.has_advertising)
    "ApplicationDebuggable" -> stringResource(R.string.compiled_for_debugging)
    "DisabledAlgorithm" -> stringResource(R.string.signed_using_unsafe_algorithm)
    "KnownVuln" -> stringResource(R.string.has_security_vulnerabilities)
    "NoSourceSince" -> stringResource(R.string.source_code_no_longer_available)
    "NonFreeAdd" -> stringResource(R.string.promotes_non_free_software)
    "NonFreeAssets" -> stringResource(R.string.contains_non_free_media)
    "NonFreeDep" -> stringResource(R.string.has_non_free_dependencies)
    "NonFreeNet" -> stringResource(R.string.promotes_non_free_network_services)
    "NSFW" -> stringResource(R.string.contains_nsfw)
    "Tracking" -> stringResource(R.string.tracks_or_reports_your_activity)
    "UpstreamNonFree" -> stringResource(R.string.upstream_source_code_is_not_free)
    "NonFreeComp" -> stringResource(R.string.has_non_free_components)
    "TetheredNet" -> stringResource(R.string.has_tethered_network)
    else -> tag
}

@Composable
private fun PermissionsSection(permissions: List<Permission>) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // TV only: a soft green fill behind the focused row (no-op on touch).
                .tvFocusFill(RoundedCornerShape(12.dp), debugLabel = "permissions-row")
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_perm_device_information),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.permissions),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            CountBadge(permissions.size)
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (expanded) {
            permissions.forEach { permission ->
                Text(
                    text = permission.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private fun String.nonBlank(): String? = takeIf { it.isNotBlank() }

private fun App.hasLinks(): Boolean = listOfNotNull(
    links?.webSite,
    links?.sourceCode,
    links?.issueTracker,
    links?.changelog,
    links?.translation,
    author?.web,
    donation?.regularUrl?.firstOrNull(),
).any { it.isNotBlank() }

@Composable
private fun ScreenshotsRow(screenshots: List<FilePath>) {
    var fullscreenIndex by remember { mutableStateOf<Int?>(null) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(screenshots, key = { _, file -> file.path }) { index, file ->
            val painter = rememberAsyncImagePainter(file.path)
            val imageState by painter.state.collectAsStateWithLifecycle()
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(180.dp)
                    .widthIn(min = 90.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    // TV only: visible focus ring on the screenshot (no-op on touch).
                    .tvFocusOutline(MaterialTheme.shapes.small)
                    .clickable { fullscreenIndex = index },
            ) {
                when (imageState) {
                    is AsyncImagePainter.State.Error -> {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                        )
                    }

                    is AsyncImagePainter.State.Success -> {
                        Image(
                            painter = painter,
                            contentDescription = stringResource(R.string.screenshot),
                            modifier = Modifier.height(200.dp),
                            contentScale = ContentScale.FillHeight,
                        )
                    }

                    else -> {}
                }
            }
        }
    }
    val startIndex = fullscreenIndex
    if (startIndex != null) {
        ScreenshotViewer(
            screenshots = screenshots,
            startIndex = startIndex,
            onDismiss = { fullscreenIndex = null },
        )
    }
}

/** Full-screen, swipeable screenshot viewer shown when a thumbnail is tapped. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScreenshotViewer(
    screenshots: List<FilePath>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val pagerState = rememberPagerState(initialPage = startIndex) { screenshots.size }
        val isTelevision = LocalIsTelevision.current
        val coroutineScope = rememberCoroutineScope()
        // Android TV must always have a focused element: this dialog opens no other focusable node, so
        // the close button is it. Also lets left/right page through the screenshots, since the pager
        // otherwise relies on a swipe gesture the remote can't produce. No effect on touch.
        val closeFocusRequester = remember { FocusRequester() }
        if (isTelevision) {
            LaunchedEffect(Unit) {
                repeat(20) {
                    if (runCatching { closeFocusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
                    delay(50)
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                .then(
                    if (isTelevision) {
                        Modifier.onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) {
                                false
                            } else if (event.key == Key.DirectionRight &&
                                pagerState.currentPage < screenshots.lastIndex
                            ) {
                                coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                true
                            } else if (event.key == Key.DirectionLeft && pagerState.currentPage > 0) {
                                coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                true
                            } else {
                                false
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !isTelevision,
            ) { page ->
                AsyncImage(
                    model = screenshots[page].path,
                    contentDescription = "screenshot",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .focusRequester(closeFocusRequester)
                    .tvFocusScale(debugLabel = "screenshot-dialog-close"),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoriesRow(categories: List<String>) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(categories) { cat ->
            FilterChip(
                selected = false,
                onClick = { },
                enabled = true,
                label = { Text(cat) },
                // These category labels do nothing when tapped, so on TV the D-pad skips them rather
                // than stopping on dead chips (which also removes the focus-zoom overlap between two
                // adjacent chips). No effect on touch.
                modifier = if (LocalIsTelevision.current) {
                    Modifier.focusProperties { canFocus = false }
                } else {
                    Modifier
                },
            )
        }
    }
}

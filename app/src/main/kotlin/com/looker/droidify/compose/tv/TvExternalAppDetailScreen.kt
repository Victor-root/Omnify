package com.looker.droidify.compose.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.looker.droidify.compose.components.InstallVersionDialog
import com.looker.droidify.compose.externalApps.ReleaseVersionItem
import com.looker.droidify.external.apkDownloadUrl
import com.looker.droidify.external.apkFileName
import com.looker.droidify.external.apkFileSize
import com.looker.droidify.external.apkUpdatedAt
import com.looker.droidify.external.compareVersionStrings
import com.looker.droidify.external.releaseVersionLabel
import com.looker.droidify.utility.apk.ApkBinaryManifest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.components.CertificateSection
import com.looker.droidify.compose.components.TvOverscan
import com.looker.droidify.compose.components.tvBringIntoViewOnFocus
import com.looker.droidify.compose.externalApps.ExternalAppIcon
import com.looker.droidify.compose.externalApps.ExternalAppsViewModel
import com.looker.droidify.compose.externalApps.ExternalLifecycleActions
import com.looker.droidify.compose.settings.components.WarningBanner
import com.looker.droidify.compose.theme.ScopedAccentColor
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.Release
import com.looker.droidify.utility.common.dominantAccentColor
import com.looker.droidify.utility.common.extension.calculateHash
import com.looker.droidify.utility.common.extension.getPackageInfoCompat
import com.looker.droidify.utility.common.extension.singleSignature
import kotlinx.coroutines.delay

/**
 * The Android TV detail screen for a tracked external (GitHub/GitLab/…) app — a lean presentation over
 * the same [ExternalAppsViewModel] the phone screen uses: icon + name + source + version, the one big
 * Install / Update / Open / Uninstall control (reused verbatim as [ExternalLifecycleActions], so the whole
 * download/install lifecycle matches the phone build), a favourite toggle, the README (opened in a
 * full-screen reader, see [TvOpenDescriptionButton]) and the release history — the same content a
 * catalogue app shows, so the two kinds read alike. Never composed off TV.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvExternalAppDetailScreen(
    appKey: String,
    viewModel: ExternalAppsViewModel,
    onBackClick: () -> Unit,
    onFixGithubToken: () -> Unit,
) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val installStates by viewModel.installStates.collectAsStateWithLifecycle()
    val installedVersions by viewModel.installedVersions.collectAsStateWithLifecycle()
    val signatureConflict by viewModel.signatureConflict.collectAsStateWithLifecycle()
    val readme by viewModel.readme.collectAsStateWithLifecycle()
    val readmeJavaScriptEnabled by viewModel.readmeJavaScriptEnabled.collectAsStateWithLifecycle()
    val releaseHistory by viewModel.releaseHistory.collectAsStateWithLifecycle()
    val sdkInfoByApkUrl by viewModel.sdkInfoByApkUrl.collectAsStateWithLifecycle()
    val expectedSignersByApkUrl by viewModel.expectedSignersByApkUrl.collectAsStateWithLifecycle()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    val githubTokenInvalid by viewModel.githubTokenInvalid.collectAsStateWithLifecycle()
    val accentMatchesAppIcon by viewModel.accentMatchesAppIcon.collectAsStateWithLifecycle()

    BackHandler { onBackClick() }

    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.reconcileInstalledLabels()
    }
    // Re-read install state on resume — notably returning from the system uninstall dialog — so the
    // button flips back to Install without leaving the screen (installManager.state alone doesn't
    // report a system uninstall).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshInstalled()
    }

    val app = apps.firstOrNull { it.key == appKey }

    if (app == null) {
        TvCentered { CircularProgressIndicator() }
        return
    }

    val context = LocalContext.current
    val installedVersion = installedVersions[appKey]
    val isInstalled = installedVersion != null
    // The installed APK's actual signing certificate (lowercase hex; not anything the source repo itself
    // could claim, this reads the real, on-device signature), so it can be shown, compared, and copied,
    // e.g. against a build the user just compiled themselves. Null when not installed, the package id
    // isn't known yet, or on the rare PackageManager read failure.
    val installedSignerRaw = remember(isInstalled, app.packageName) {
        val packageName = app.packageName
        if (!isInstalled || packageName == null) {
            null
        } else {
            context.packageManager.getPackageInfoCompat(packageName)
                ?.singleSignature
                ?.calculateHash()
        }
    }
    // Set once the hero icon actually loads (see the ExternalAppIcon call below); reset per screen
    // instance, i.e. per app, since a different app's detail page is a fresh composition of this screen.
    var iconAccentColor by remember { mutableStateOf<Int?>(null) }

    // Make sure the app knows the package id it installs under, so a copy already on the device (installed
    // from any channel) is recognised as installed instead of showing "Install". Keyed on the resolved app
    // (not the raw key) so it runs once the app record has actually loaded. No-op once the id is known.
    LaunchedEffect(app.key) { viewModel.ensurePackageId(app.key) }

    // Expected certificate: read from the release APK itself, the only source available before the app
    // is ever installed (there's no F-Droid-style index declaring it ahead of time here).
    LaunchedEffect(app.latestApkUrl) {
        app.latestApkUrl?.let(viewModel::loadExpectedSigners)
    }
    val expectedSigners = app.latestApkUrl?.let { expectedSignersByApkUrl[it] }

    // Same content the phone screen loads, so an external app's page is as rich as a catalogue one
    // (Omnify deliberately blurs the line between the two).
    LaunchedEffect(app.key) {
        viewModel.loadReadme(app)
        viewModel.loadReleaseHistory(app)
    }
    var showDescription by remember(app.key) { mutableStateOf(false) }
    if (showDescription && readme != null) {
        ScopedAccentColor(if (accentMatchesAppIcon) iconAccentColor else null) {
            TvReadmeScreen(
                title = stringResource(R.string.description),
                html = readme,
                unavailable = false,
                unavailableMessage = "",
                baseUrl = app.readmeWebBaseUrl,
                javaScriptEnabled = readmeJavaScriptEnabled,
                webUrl = app.webUrl,
                onBack = { showDescription = false },
            )
        }
        return
    }

    // Tapping a version asks to confirm, then installs that exact release — the same engine
    // (viewModel.installVersion) and confirmation dialog the phone screen uses.
    var versionToInstall by remember(app.key) { mutableStateOf<Release?>(null) }
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

    // Signature-conflict prompt (a different-signer install can't update in place), reused from the
    // phone screen's own flow.
    signatureConflict?.let { conflict ->
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
            text = { Text(stringResource(messageRes, app.label)) },
            confirmButton = {
                if (conflict.isSystemApp) {
                    TextButton(onClick = viewModel::dismissSignatureConflict) {
                        Text(stringResource(android.R.string.ok))
                    }
                } else {
                    TextButton(onClick = viewModel::confirmSignatureConflictUninstall) {
                        Text(stringResource(R.string.uninstall))
                    }
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

    val primaryFocus = remember { FocusRequester() }
    var userInteracted by remember { mutableStateOf(false) }
    // Screen height, so the README preview can fill from its top down to the bottom edge.
    var viewportPx by remember { mutableStateOf(0) }
    // Land focus on the action button when the screen opens; stop once the user takes over (same intent
    // as the catalogue TV detail screen).
    LaunchedEffect(app.key, installedVersion) {
        if (userInteracted) return@LaunchedEffect
        repeat(20) {
            if (runCatching { primaryFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(50)
        }
    }

    // The header (back button, icon/name/chips, action buttons + favourite) is guaranteed to already fit
    // in the initial viewport, so navigating the D-pad within it should never scroll the page — only
    // leaving it, into the description/versions below, should. Compose's own "scroll the newly focused
    // element into view" reflex doesn't respect that (it still nudges the scroll position for an element
    // that's already fully visible, most visibly as a shaky micro up/down scroll while tabbing between the
    // action buttons), so it's suppressed outright while focus is inside the header and the page is still
    // at the top — the same fix already shipped for the phone hero card (see AppDetailScreen), never
    // ported to this separate TV screen when it was split out.
    val pageScroll = rememberScrollState()
    var headerHasFocus by remember { mutableStateOf(true) }
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val bringIntoViewSpec = remember(defaultBringIntoViewSpec) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float = if (headerHasFocus && pageScroll.value == 0) {
                0f
            } else {
                defaultBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize)
            }
        }
    }

    // Scoped to just this screen: when the setting is on and a colour has been sampled from the app's own
    // icon, it overrides the accent for every MaterialTheme.colorScheme.primary use inside (e.g. the
    // favourite heart, the primary action button), without touching the app-wide theme.
    ScopedAccentColor(if (accentMatchesAppIcon) iconAccentColor else null) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    TvAccentBackground()
    CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewportPx = it.height }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) userInteracted = true
                false
            }
            // overscrollEffect = null: the README preview hosts a hardware-accelerated WebView, and
            // Android 12+'s stretch overscroll crashes RenderThread when redrawing it at the scroll
            // boundary (same reason as the phone detail screen).
            .verticalScroll(pageScroll, overscrollEffect = null)
            .padding(horizontal = TvOverscan + 16.dp, vertical = TvOverscan),
        verticalArrangement = spacedBy(24.dp),
    ) {
        // A token GitHub is actively rejecting silently stops this very source from refreshing (see
        // ExternalAppsViewModel.refresh), with nothing else on this page otherwise showing anything is
        // wrong. Kept outside the focus-group Column below: it's a separate D-pad stop, not part of the
        // header block the BringIntoViewSpec above suppresses scroll for.
        if (githubTokenInvalid) {
            WarningBanner(
                title = stringResource(R.string.external_token_invalid_title),
                description = stringResource(R.string.external_token_invalid_DESC),
                onClick = onFixGithubToken,
            )
        }
        Column(
            modifier = Modifier.focusGroup().onFocusChanged { headerHasFocus = it.hasFocus },
            verticalArrangement = spacedBy(24.dp),
        ) {
            TvBackButton(onBackClick)

            Row(horizontalArrangement = spacedBy(24.dp)) {
                Box(modifier = Modifier.padding(top = 4.dp)) {
                    ExternalAppIcon(
                        app = app,
                        isInstalled = isInstalled,
                        size = 112.dp,
                        onIconBitmap = if (accentMatchesAppIcon) {
                            { bitmap -> iconAccentColor = bitmap.dominantAccentColor() }
                        } else {
                            null
                        },
                    )
                }
                Column(verticalArrangement = spacedBy(8.dp)) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${app.owner}/${app.repo}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = spacedBy(8.dp), verticalArrangement = spacedBy(8.dp)) {
                        // Same version as the version list below: pulled from the latest APK's file name
                        // (releaseVersionLabel), falling back to the tag — NOT the raw GitHub tag, which
                        // can differ from the APK's real version. Mirrors the phone screen's hero version.
                        releaseVersionLabel(app.latestApkName, app.latestTag).takeIf { it.isNotBlank() }
                            ?.let { TvChip(it) }
                        TvChip(app.provider.name.lowercase().replaceFirstChar { it.uppercase() })
                        if (app.supportsTelevision) TvChip(stringResource(R.string.discover_tv_apps))
                    }
                }
            }

            // Action buttons with the favourite alongside, sized to content and centred as one group
            // (same treatment as the catalogue detail screen) so the favourite reads as a peer, not an
            // outlier.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = spacedBy(28.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ExternalLifecycleActions(
                    app = app,
                    downloadStatus = downloads[appKey],
                    installState = installStates[appKey],
                    isInstalled = isInstalled,
                    onInstallOrUpdate = { viewModel.installOrUpdate(app) },
                    onLaunch = { viewModel.launch(app) },
                    onUninstall = { viewModel.uninstall(app) },
                    onCancel = { viewModel.cancel(app) },
                    modifier = Modifier.width(IntrinsicSize.Min),
                    installedVersionName = installedVersion,
                    primaryActionFocusRequester = primaryFocus,
                )
                TvFavouriteButton(
                    isFavourite = app.key in favourites,
                    onToggle = { viewModel.toggleFavourite(app) },
                )
            }
        }

        if (installedSignerRaw != null || !expectedSigners.isNullOrEmpty()) {
            CertificateSection(
                installedSigner = installedSignerRaw,
                expectedSigners = expectedSigners,
            )
        }

        readme?.takeIf { it.isNotBlank() }?.let { readmeHtml ->
            TvSectionTitle(stringResource(R.string.description))
            TvReadmePreview(
                html = readmeHtml,
                baseUrl = app.readmeWebBaseUrl,
                javaScriptEnabled = readmeJavaScriptEnabled,
                viewportPx = viewportPx,
                onOpenFull = { showDescription = true },
            )
        }

        releaseHistory?.takeIf { it.isNotEmpty() }?.let { releases ->
            TvSectionTitle(stringResource(R.string.versions))
            TvExternalVersionsList(
                app = app,
                releases = releases,
                installedVersion = installedVersion,
                sdkInfoByApkUrl = sdkInfoByApkUrl,
                onRequestSdkInfo = viewModel::loadSdkInfo,
                onVersionClick = { versionToInstall = it },
            )
        }
    }
    }
    }
    }
}

/**
 * The external app's version list — the exact same engine as the phone screen (version label pulled
 * from the APK's own file name via [releaseVersionLabel], not the raw tag; installed match via
 * [compareVersionStrings] against the real on-device version; per-APK min/target SDK fetched lazily) and
 * the exact same [ReleaseVersionItem] rows, so a version reads identically to the phone build. Only the
 * surrounding TV layout differs.
 */
@Composable
private fun TvExternalVersionsList(
    app: ExternalApp,
    releases: List<Release>,
    installedVersion: String?,
    sdkInfoByApkUrl: Map<String, ApkBinaryManifest.UsesSdk?>,
    onRequestSdkInfo: (String) -> Unit,
    onVersionClick: (Release) -> Unit,
) {
    Column(verticalArrangement = spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        releases.forEach { release ->
            val apkName = release.apkFileName(filter = app.apkFilter)
            val isInstalled = installedVersion != null &&
                compareVersionStrings(releaseVersionLabel(apkName, release.tag), installedVersion) == 0
            val apkUrl = release.apkDownloadUrl(filter = app.apkFilter)
            if (apkUrl != null) {
                LaunchedEffect(apkUrl) { onRequestSdkInfo(apkUrl) }
            }
            ReleaseVersionItem(
                release = release,
                apkName = apkName,
                apkSize = release.apkFileSize(filter = app.apkFilter),
                apkDate = release.apkUpdatedAt(filter = app.apkFilter),
                sdkInfo = apkUrl?.let { sdkInfoByApkUrl[it] },
                isSuggested = release.tag == app.latestTag,
                isInstalled = isInstalled,
                onClick = { onVersionClick(release) },
                modifier = Modifier.tvBringIntoViewOnFocus(),
            )
        }
    }
}

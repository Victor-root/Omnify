package com.looker.droidify.compose.tv

import com.looker.droidify.compose.components.HeroStatsRow
import com.looker.droidify.network.DataSize
import com.looker.droidify.compose.components.RootBadge
import com.looker.droidify.compose.components.SupportedLanguagesSection
import com.looker.droidify.utility.common.RootDetection
import com.looker.droidify.utility.common.extension.openAppInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.looker.droidify.compose.components.InstallConflictDialog
import com.looker.droidify.migration.ChannelSwitchBanner
import com.looker.droidify.compose.components.InstallVersionDialog
import com.looker.droidify.compose.externalApps.ReleaseVersionItem
import com.looker.droidify.compose.externalApps.RepositoryReadmeNote
import com.looker.droidify.compose.externalApps.releaseVersionRows
import com.looker.droidify.external.releaseVersionLabel
import com.looker.droidify.utility.apk.ApkBinaryManifest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.appDetail.DownloadStatus
import com.looker.droidify.compose.components.CertificateSection
import com.looker.droidify.compose.components.LoadingSpinner
import com.looker.droidify.compose.externalApps.ExternalAppIcon
import com.looker.droidify.compose.externalApps.ExternalAppsViewModel
import com.looker.droidify.compose.externalApps.ExternalLifecycleActions
import com.looker.droidify.compose.settings.components.WarningBanner
import com.looker.droidify.compose.theme.ScopedAccentColor
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.RELEASE_HISTORY_TARGET
import com.looker.droidify.external.Release
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.utility.common.rememberIconAccent
import com.looker.droidify.utility.common.extension.calculateHash
import com.looker.droidify.utility.common.extension.getPackageInfoCompat
import com.looker.droidify.utility.common.extension.singleSignature

/**
 * The Android TV detail screen for a tracked external (GitHub/GitLab/…) app — a lean presentation over
 * the same [ExternalAppsViewModel] the phone screen uses: icon + name + source + version, the one big
 * Install / Update / Open / Uninstall control (reused verbatim as [ExternalLifecycleActions], so the whole
 * download/install lifecycle matches the phone build), the discreet favourite and hide toggles, the README
 * and the release history, in the same two panes as a catalogue app (see [TvAppDetailPanes]), so the two
 * kinds read alike. Never composed off TV.
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
    val installConflict by viewModel.installConflict.collectAsStateWithLifecycle()
    val readme by viewModel.readme.collectAsStateWithLifecycle()
    val supportedLanguages by viewModel.supportedLanguages.collectAsStateWithLifecycle()
    val readmeJavaScriptEnabled by viewModel.readmeJavaScriptEnabled.collectAsStateWithLifecycle()
    val releaseHistory by viewModel.releaseHistory.collectAsStateWithLifecycle()
    val sdkInfoByApkUrl by viewModel.sdkInfoByApkUrl.collectAsStateWithLifecycle()
    val expectedSignersByApkUrl by viewModel.expectedSignersByApkUrl.collectAsStateWithLifecycle()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    val hiddenApps by viewModel.hidden.collectAsStateWithLifecycle()
    val readmeError by viewModel.readmeError.collectAsStateWithLifecycle()
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

    val app = remember(apps, appKey) { apps.firstOrNull { it.key == appKey } }
    TvDetailOpenTiming("external app detail", ready = app != null)

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
    // Set as the hero icon loads (see the ExternalAppIcon call below); reset per screen
    // instance, i.e. per app, since a different app's detail page is a fresh composition of this screen.
    // Follows the picture on screen: the app's own icon read off the device replaces the one shown before
    // it, and the accent has to follow it.
    val iconAccent = rememberIconAccent(
        cacheKey = "ext:$appKey",
        followsPicture = true,
        enabled = accentMatchesAppIcon,
    )

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

    // Read off the same text as the phone screen's badge: the app's name and its README.
    val isRootCompatible = remember(app.key, readme) {
        RootDetection.textIndicatesRoot("${app.label} ${readme?.html.orEmpty()}")
    }

    // Same content the phone screen loads, so an external app's page is as rich as a catalogue one
    // (Omnify deliberately blurs the line between the two). Unlike the phone screen, TV's version
    // list has no "show more" (see TvExternalVersionsList below), so it asks for the full ceiling
    // straight away rather than the smaller amount the phone starts with.
    LaunchedEffect(app.key) {
        viewModel.loadReadme(app)
        viewModel.loadReleaseHistory(app, limit = RELEASE_HISTORY_TARGET)
    }
    // Re-checked when the install state changes, like the phone screen: right after an install it
    // switches from the remote check to the installed APK's own locales.
    LaunchedEffect(app.key, isInstalled) { viewModel.loadSupportedLanguages(app, isInstalled) }
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

    // Install-conflict prompt (a different-signer install, or an older version code, can't update in
    // place), reused from the phone screen's own flow.
    installConflict?.let { conflict ->
        InstallConflictDialog(
            conflict = conflict,
            appName = app.label,
            onUninstall = viewModel::confirmInstallConflictUninstall,
            onDismiss = viewModel::dismissInstallConflict,
        )
    }

    val primaryFocus = remember { FocusRequester() }
    var userInteracted by remember { mutableStateOf(false) }
    // The header (back button, icon/name/chips, action buttons + favourite) is guaranteed to already fit
    // in the initial viewport, so navigating the D-pad within it should never scroll the page — only
    // leaving it, into the description/versions below, should. Compose's own "scroll the newly focused
    // element into view" reflex doesn't respect that (it still nudges the scroll position for an element
    // that's already fully visible, most visibly as a shaky micro up/down scroll while tabbing between the
    // action buttons), so it's suppressed outright while focus is inside the header and the page is still
    // at the top — the same fix already shipped for the phone hero card (see AppDetailScreen), never
    // ported to this separate TV screen when it was split out.
    val pageScroll = rememberScrollState()
    var headerHasFocus by remember { mutableStateOf(false) }
    // The action buttons alone, not the whole header: the favourite and hide toggles sit in it too, and
    // focus landing on one of those was taken for having reached Install.
    var actionsHaveFocus by remember { mutableStateOf(false) }
    val bringIntoViewSpec = rememberTvLeftPaneBringIntoViewSpec(pageScroll) { headerHasFocus }

    TvKeepFocusOnMainAction(
        primary = primaryFocus,
        actionHasFocus = { actionsHaveFocus },
        userInteracted = { userInteracted },
    )

    // Scoped to just this screen: when the setting is on and a colour has been sampled from the app's own
    // icon, it overrides the accent for every MaterialTheme.colorScheme.primary use inside (e.g. the
    // favourite heart, the primary action button), without touching the app-wide theme.
    ScopedAccentColor(if (accentMatchesAppIcon) iconAccent.accent else null) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TvAccentBackground()
            TvAppDetailPanes(
                leftScroll = pageScroll,
                leftBringIntoViewSpec = bringIntoViewSpec,
                onKeyDown = { userInteracted = true },
                left = {
                    // A token GitHub is actively rejecting silently stops this very source from refreshing
                    // (see ExternalAppsViewModel.refresh), with nothing else on this page otherwise showing
                    // anything is wrong. Kept outside the focus-group Column below: it's a separate D-pad
                    // stop, not part of the header block the BringIntoViewSpec above suppresses scroll for.
                    if (githubTokenInvalid) {
                        WarningBanner(
                            title = stringResource(R.string.external_token_invalid_title),
                            description = stringResource(R.string.external_token_invalid_DESC),
                            onClick = onFixGithubToken,
                        )
                    }
                    Column(
                        modifier = Modifier.focusGroup().onFocusChanged { headerHasFocus = it.hasFocus },
                        verticalArrangement = spacedBy(20.dp),
                    ) {
                        Row(horizontalArrangement = spacedBy(20.dp)) {
                            Box(modifier = Modifier.padding(top = 4.dp)) {
                                ExternalAppIcon(
                                    app = app,
                                    isInstalled = isInstalled,
                                    size = 96.dp,
                                    onIconBitmap = iconAccent.onIconBitmap,
                                )
                            }
                            Column(verticalArrangement = spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "${app.owner}/${app.repo}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (isRootCompatible) RootBadge()
                            }
                        }
                        // Under the icon, across the card: beside it, the column was too narrow for two of
                        // them and each landed on a line of its own.
                        FlowRow(horizontalArrangement = spacedBy(8.dp), verticalArrangement = spacedBy(8.dp)) {
                            TvChip(app.provider.name.lowercase().replaceFirstChar { it.uppercase() })
                            if (app.supportsTelevision) TvChip(stringResource(R.string.discover_tv_apps))
                        }
                        // Version and size as the phone's card shows them, not as tags. The version is the
                        // latest APK's own (releaseVersionLabel), as in the version list below, not the raw
                        // tag, which can differ from it.
                        HeroStatsRow(
                            version = releaseVersionLabel(app.latestApkName, app.latestTag).takeIf { it.isNotBlank() },
                            size = app.latestApkSize?.let { DataSize(it).toString() },
                            onSourceCodeClick = null,
                        )

                        // The built-in Omnify source offering the other release channel: an install, not an
                        // update, and the action row below deliberately no longer offers it.
                        if (app.offersStableSwitch) {
                            ChannelSwitchBanner(onInstallStable = { viewModel.installOrUpdate(app) })
                        }
                        // Action buttons sharing the pane's width (same treatment as the catalogue detail
                        // screen), then the favourite and hide toggles, discreet since they are rarely used.
                        Box(modifier = Modifier.onFocusChanged { actionsHaveFocus = it.hasFocus }) {
                            TvExternalActions(
                                app = app,
                                viewModel = viewModel,
                                downloadStatus = { downloads[appKey] },
                                installState = installStates[appKey],
                                isInstalled = isInstalled,
                                installedVersionName = installedVersion,
                                primaryActionFocusRequester = primaryFocus,
                            )
                        }
                        TvSecondaryActions(
                            isFavourite = app.key in favourites,
                            onToggleFavourite = { viewModel.toggleFavourite(app) },
                            isHidden = app.key in hiddenApps,
                            onToggleHidden = { viewModel.toggleHidden(app) },
                            onManageClick = app.packageName?.takeIf { isInstalled }?.let { packageName ->
                                { context.openAppInfo(packageName) }
                            },
                        )
                    }

                    supportedLanguages?.let { SupportedLanguagesSection(languages = it) }

                    if (installedSignerRaw != null || !expectedSigners.isNullOrEmpty()) {
                        CertificateSection(
                            installedSigner = installedSignerRaw,
                            expectedSigners = expectedSigners,
                        )
                    }

                    releaseHistory?.takeIf { it.isNotEmpty() }?.let { releases ->
                        TvSectionTitle(stringResource(R.string.versions))
                        TvExternalVersionsList(
                            app = app,
                            releases = releases,
                            installedVersion = installedVersion,
                            sdkInfoByApkUrl = { sdkInfoByApkUrl },
                            onRequestSdkInfo = viewModel::loadSdkInfo,
                            onVersionClick = { versionToInstall = it },
                        )
                    }
                },
                right = {
                    TvSectionTitle(stringResource(R.string.description))
                    val readmeHtml = readme?.html
                    if (!readmeHtml.isNullOrBlank() && readme?.ofRepository == true) {
                        RepositoryReadmeNote(
                            repoPath = app.path,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    when {
                        !readmeHtml.isNullOrBlank() -> TvDescriptionPane(
                            html = readmeHtml,
                            baseUrl = app.readmeWebBaseUrl,
                            javaScriptEnabled = readmeJavaScriptEnabled,
                            webUrl = app.webUrl,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            leftTarget = primaryFocus,
                        )
                        readmeError != null -> TvDescriptionMessage(readmeError.orEmpty())
                        readmeHtml != null -> TvDescriptionMessage(stringResource(R.string.no_description_available_DESC))
                        else -> LoadingSpinner(modifier = Modifier.padding(16.dp).size(32.dp))
                    }
                },
            )
        }
    }
}

/** [ExternalLifecycleActions] reading [downloadStatus] itself, so a download's progress ticks
 *  recompose only these buttons instead of the whole pane around them. */
@Composable
private fun TvExternalActions(
    app: ExternalApp,
    viewModel: ExternalAppsViewModel,
    downloadStatus: () -> DownloadStatus?,
    installState: InstallState?,
    isInstalled: Boolean,
    installedVersionName: String?,
    primaryActionFocusRequester: FocusRequester,
) {
    ExternalLifecycleActions(
        app = app,
        downloadStatus = downloadStatus(),
        installState = installState,
        isInstalled = isInstalled,
        onInstallOrUpdate = { viewModel.installOrUpdate(app) },
        onLaunch = { viewModel.launch(app) },
        onUninstall = { viewModel.uninstall(app) },
        onCancel = { viewModel.cancel(app) },
        installedVersionName = installedVersionName,
        primaryActionFocusRequester = primaryActionFocusRequester,
        fillWidth = true,
    )
}

/**
 * The external app's version list — the exact same engine as the phone screen (version label pulled
 * from the APK's own file name via [releaseVersionLabel], not the raw tag; installed match against the
 * real on-device version, both from [releaseVersionRows]; per-APK min/target SDK fetched lazily) and
 * the exact same [ReleaseVersionItem] rows, so a version reads identically to the phone build. Only the
 * surrounding TV layout differs.
 */
@Composable
private fun TvExternalVersionsList(
    app: ExternalApp,
    releases: List<Release>,
    installedVersion: String?,
    // Read here, not by the caller, so each APK's SDK info landing recomposes only this list.
    sdkInfoByApkUrl: () -> Map<String, ApkBinaryManifest.UsesSdk?>,
    onRequestSdkInfo: (String) -> Unit,
    onVersionClick: (Release) -> Unit,
) {
    val rows = remember(releases, app.apkFilter, installedVersion) {
        releaseVersionRows(releases, app.apkFilter, installedVersion)
    }
    val sdkInfo = sdkInfoByApkUrl()
    Column(verticalArrangement = spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            val apkUrl = row.apkUrl
            if (apkUrl != null) {
                LaunchedEffect(apkUrl) { onRequestSdkInfo(apkUrl) }
            }
            ReleaseVersionItem(
                release = row.release,
                apkName = row.apkName,
                apkSize = row.apkSize,
                apkDate = row.apkDate,
                sdkInfo = apkUrl?.let { sdkInfo[it] },
                isSuggested = row.release.tag == app.latestTag,
                isInstalled = row.isInstalled,
                onClick = { onVersionClick(row.release) },
            )
        }
    }
}

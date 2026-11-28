package com.looker.droidify.compose.tv

import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.ui.platform.LocalContext
import com.looker.droidify.compose.appDetail.PermissionsSection
import com.looker.droidify.compose.appDetail.automaticBuild
import com.looker.droidify.compose.appDetail.capabilities
import com.looker.droidify.compose.appDetail.updateBuild
import com.looker.droidify.compose.components.CapabilityBadges
import com.looker.droidify.compose.components.SupportedLanguagesSection
import com.looker.droidify.compose.components.rememberInstalledCodeCapabilities
import com.looker.droidify.compose.externalApps.ChangelogDialog
import com.looker.droidify.compose.externalApps.PrereleaseModeChooser
import com.looker.droidify.external.PrereleaseMode
import com.looker.droidify.external.linkedChangelog
import com.looker.droidify.utility.common.extension.openAppInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.appDetail.AppDetailState
import com.looker.droidify.compose.appDetail.AppDetailViewModel
import com.looker.droidify.compose.appDetail.DownloadStatus
import com.looker.droidify.compose.appDetail.MAX_VERSIONS_SHOWN
import com.looker.droidify.compose.appDetail.PrimaryActions
import com.looker.droidify.compose.appList.AppMinimalIcon
import com.looker.droidify.compose.appDetail.components.PackageItem
import com.looker.droidify.compose.components.InstallConflictDialog
import com.looker.droidify.data.catalogueBuildIsOlder
import com.looker.droidify.data.model.Package
import com.looker.droidify.data.model.Repo
import com.looker.droidify.installer.model.InstallState
import androidx.compose.foundation.layout.Arrangement.spacedBy
import com.looker.droidify.compose.components.AppTagline
import com.looker.droidify.compose.components.CertificateSection
import com.looker.droidify.compose.components.DescriptionTranslation
import com.looker.droidify.compose.components.HeroStatsRow
import com.looker.droidify.compose.components.TvOverscan
import com.looker.droidify.compose.components.summaryOr
import com.looker.droidify.compose.components.tvBringIntoViewOnFocus
import com.looker.droidify.compose.components.tvFocusScale
import com.looker.droidify.compose.theme.ScopedAccentColor
import com.looker.droidify.data.model.minimal
import com.looker.droidify.data.model.selectForDevice
import com.looker.droidify.utility.common.rememberIconAccent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.looker.droidify.compose.appDetail.FollowSourceRow
import com.looker.droidify.compose.appDetail.SourceFollowDialogs
import com.looker.droidify.compose.appDetail.catalogueSourceNote
import com.looker.droidify.compose.appDetail.rememberVersionInstallState
import com.looker.droidify.compose.appDetail.VersionInstallDialogs
import com.looker.droidify.compose.appDetail.SourceTab
import com.looker.droidify.compose.appDetail.rememberSourceFollowState
import com.looker.droidify.compose.appDetail.sourceNameOf
import com.looker.droidify.compose.appDetail.sourceTabs
import com.looker.droidify.compose.appDetail.updatingSourceOf
import com.looker.droidify.compose.theme.SOURCE_SWITCH_MS
import com.looker.droidify.compose.externalApps.ExternalAppsViewModel
import com.looker.droidify.compose.theme.EmphasizedAccelerate
import com.looker.droidify.compose.theme.EmphasizedDecelerate
import com.looker.droidify.data.FollowedSource
import com.looker.droidify.external.ExternalApp

/**
 * The Android TV app-detail screen — a lean, couch-friendly presentation over the same
 * [AppDetailViewModel] the phone screen uses, in two panes like the tablet split view: on the left icon +
 * name + a few meta chips, one big Install / Update / Launch button that holds the startup focus, the
 * discreet favourite and hide toggles, the certificate and the version list; on the right the screenshots
 * and the whole description (see [TvAppDetailPanes]).
 * The heavy install lifecycle is reused verbatim ([PrimaryActions]), so behaviour matches the phone
 * build exactly; only the layout is TV-specific. Never composed off TV.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvAppDetailScreen(
    onBackClick: () -> Unit,
    viewModel: AppDetailViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val installState by viewModel.installState.collectAsStateWithLifecycle()
    val downloadStatus by viewModel.downloadStatus.collectAsStateWithLifecycle()
    val downloadTargetVersionCode by viewModel.downloadTargetVersionCode.collectAsStateWithLifecycle()
    val isFavourite by viewModel.isFavourite.collectAsStateWithLifecycle()
    val isHidden by viewModel.isHidden.collectAsStateWithLifecycle()
    val installedInfo by viewModel.installedInfo.collectAsStateWithLifecycle()
    val externallySourced by viewModel.externallySourced.collectAsStateWithLifecycle()
    val updateSource by viewModel.updateSource.collectAsStateWithLifecycle()
    val externalSources by viewModel.externalSources.collectAsStateWithLifecycle()
    val externalUpdater by viewModel.externalUpdater.collectAsStateWithLifecycle()
    // Only for an app a tracked source also offers, as on the phone page.
    val externalViewModel: ExternalAppsViewModel? =
        if (externalSources.isNotEmpty() || externalUpdater != null) hiltViewModel() else null
    val accentMatchesAppIcon by viewModel.accentMatchesAppIcon.collectAsStateWithLifecycle()
    val signatureConflict by viewModel.signatureConflict.collectAsStateWithLifecycle()
    val supportedLanguages by viewModel.supportedLanguages.collectAsStateWithLifecycle()
    val descriptionTranslation by viewModel.descriptionTranslation.collectAsStateWithLifecycle()
    val translationEnabled by viewModel.translationEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Set once the hero icon actually loads (see the AppMinimalIcon call below); reset per screen
    // instance, i.e. per app, since a different app's detail page is a fresh composition of this screen.
    val iconAccent = rememberIconAccent(
        cacheKey = "pkg:${viewModel.packageName}",
        followsPicture = false,
        enabled = accentMatchesAppIcon,
    )

    BackHandler { onBackClick() }
    TvDetailOpenTiming("app detail", ready = state is AppDetailState.Success)

    // The backdrop is laid once, under the loading state as under the page: opening a page showed a flat
    // white (or black) screen until the page arrived, and the backdrop was then built anew.
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScopedAccentColor(if (accentMatchesAppIcon) iconAccent.accent else null) { TvAccentBackground() }
        when (val current = state) {
            is AppDetailState.Loading -> TvCentered { CircularProgressIndicator() }
            is AppDetailState.NotFound -> TvCentered {
                Text(
                    stringResource(R.string.app_not_found_FORMAT, current.packageName),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is AppDetailState.Success -> {
                val app = current.app
                val packages = current.packages

                val installedPackage = remember(packages) { packages.map { it.first }.firstOrNull { it.installed } }
                val appMinimal = remember(app) { app.minimal() }
                // installedInfo (real PackageManager state), not installedPackage (an exact catalogue
                // versionCode match): an app installed from a different channel that's ahead of this repo,
                // Google Play shipping a build newer than the repo's own top release, say, has no matching
                // entry in [packages] at all, which used to make isInstalled/updateAvailable below wrongly
                // report "not installed" (mirrors the same fix on the phone screen's AppDetail).
                val isInstalled = installedInfo != null
                // A plain local val (not the installedInfo delegate above) so it smart-casts below: reading
                // installedInfo?.versionCode again after an installedInfo != null check doesn't narrow the
                // delegated property itself, since a `by ... collectAsStateWithLifecycle()` getter isn't
                // guaranteed to keep returning the same instance the way a real local val does.
                val installedVersionCode = installedInfo?.versionCode
                // The installed APK's actual signing certificate (lowercase hex): not the catalogue entry's
                // own claim (just whatever the index says), but the real, on-device signature, so it can be
                // shown, compared, and copied, e.g. against a build the user just compiled themselves. Null
                // when not installed, or on the rare PackageManager read failure. Read off the main thread
                // along with the rest of installedInfo (see InstalledInfo.signer).
                val installedSignerRaw = installedInfo?.signer
                // Signed with the installed copy's key when any repository publishes one (see automaticBuild).
                val installable = remember(packages, app.metadata.suggestedVersionCode, installedSignerRaw) {
                    packages.automaticBuild(app.metadata.suggestedVersionCode, installedSignerRaw)
                }
                val installablePackage = installable?.first
                // The certificate(s) the repository index declares for the version we'd actually install/
                // update to, known the moment the catalogue is synced, whether or not the app is installed
                // yet, unlike [installedSignerRaw].
                val expectedSigners = installablePackage?.manifest?.signer
                // The same lookups the phone page makes, so both show the same things.
                val suggestedPackage = installablePackage ?: packages.firstOrNull()?.first
                val permissions = remember(suggestedPackage) { suggestedPackage?.manifest?.permissions.orEmpty() }
                val codeCapabilities = rememberInstalledCodeCapabilities(
                    app.metadata.packageName.name.takeIf { installedInfo?.signatureMismatch == false },
                    installedVersionCode,
                )
                val capabilities = remember(app, installablePackage, installedPackage, codeCapabilities) {
                    app.capabilities(installablePackage ?: installedPackage) or codeCapabilities
                }
                // externallySourced: mirrors the phone screen, see AppDetailViewModel's own property. A copy
                // a tracked external source is responsible for is not this catalogue entry's to update, and
                // the comparison below says nothing about which build is newer across two numberings. The
                // build compared is the followed repository's, when one is followed for updates.
                val updatePackage = remember(
                    packages,
                    updateSource.followed,
                    app.metadata.suggestedVersionCode,
                    installedSignerRaw,
                ) {
                    packages.updateBuild(updateSource.followed, app.metadata.suggestedVersionCode, installedSignerRaw)
                }?.first
                // A tracked source taking the updates offers its own, as on the phone page.
                val updater = externalUpdater
                val externalUpdate = updater?.takeIf { it.isUpdatePending(installedInfo?.version) }
                val updateAvailable = externalUpdate != null || (
                    !externallySourced && installedVersionCode != null &&
                        updatePackage != null &&
                        !catalogueBuildIsOlder(installedInfo?.version, updatePackage.manifest.versionName) &&
                        installedVersionCode < updatePackage.manifest.versionCode
                    )
                val sourceNote = catalogueSourceNote(viewModel)
                // Mirrors the phone screen: the OS reports Installed before the app's own installed-packages
                // table catches up, so hold the "installing" look until the confirmed version matches.
                val installConfirmed = downloadTargetVersionCode == null ||
                    installedPackage?.manifest?.versionCode == downloadTargetVersionCode

                val primaryFocus = remember { FocusRequester() }
                // Hoisted so the startup focus burst below can pin the page back to the top: focusing the
                // action button nudges the scroll down a hair (bring-into-view), which was clipping the header.
                val pageScroll = rememberScrollState()
                // Once the user has pressed any key, focus is theirs: see TvKeepFocusOnMainAction.
                var userInteracted by remember { mutableStateOf(false) }
                val tvScreenshots = app.screenshots?.tv.orEmpty()
                val screenshots = remember(app.screenshots) {
                    tvScreenshots.ifEmpty { app.screenshots?.phone.orEmpty() }
                }
                // The catalogue description is HTML, rendered in the right pane (see TvDescriptionPane) so it
                // looks the same as an external app's README.
                val descriptionHtml = app.metadata.description.raw
                val summary = app.metadata.summary
                // The summary and description are shown here, so only they are translated (no what's new).
                LaunchedEffect(app.metadata.packageName.name) {
                    viewModel.maybeAutoTranslate(summary = summary, descriptionHtml = descriptionHtml, whatsNew = "")
                }
                // Where "up" from the description goes: back to the screenshots above it.
                val screenshotsFocus = remember { FocusRequester() }

                // The changelog, read in its own page when its link allows it: there is no browser to send
                // it to on a TV.
                val linkedChangelog = remember(app.links?.changelog) { app.links?.changelog?.let(::linkedChangelog) }
                var showChangelog by remember { mutableStateOf(false) }
                if (showChangelog && linkedChangelog != null) {
                    ChangelogDialog(
                        page = viewModel.changelog,
                        translationEnabled = translationEnabled,
                        baseUrl = linkedChangelog.baseUrl,
                        javaScriptEnabled = false,
                        webUrl = app.links?.changelog.orEmpty(),
                        onDismiss = {
                            showChangelog = false
                            viewModel.changelog.dismiss()
                        },
                    )
                }

                // An update Android won't apply in place (a differently-signed copy on the device). The
                // phone screen and the TV external-source screen both showed this; this one never did, so
                // updating such an app here did nothing at all and left the button offering the same
                // update again, with no way to find out why.
                signatureConflict?.let { conflict ->
                    InstallConflictDialog(
                        conflict = conflict,
                        appName = app.metadata.name,
                        onUninstall = viewModel::confirmSignatureConflictUninstall,
                        onDismiss = viewModel::dismissSignatureConflict,
                    )
                }
                externalViewModel?.let { external ->
                    val externalConflict by external.installConflict.collectAsStateWithLifecycle()
                    externalConflict?.let { conflict ->
                        InstallConflictDialog(
                            conflict = conflict,
                            appName = app.metadata.name,
                            onUninstall = external::confirmInstallConflictUninstall,
                            onDismiss = external::dismissInstallConflict,
                        )
                    }
                }

                // The header (back button, icon/name/chips, action buttons + favourite) is guaranteed to
                // already fit in the initial viewport, so navigating the D-pad within it should never scroll
                // the page — only leaving it, into the screenshots/description/versions below, should.
                // Compose's own "scroll the newly focused element into view" reflex doesn't respect that (it
                // still nudges the scroll position for an element that's already fully visible, most visibly
                // as a shaky micro up/down scroll while tabbing between the action buttons), so it's suppressed
                // outright while focus is inside the header and the page is still at the top — the same fix
                // already shipped for the phone hero card (see AppDetailScreen), never ported to this separate
                // TV screen when it was split out.
                var headerHasFocus by remember { mutableStateOf(false) }
                // The action buttons alone, not the whole header: the favourite and hide toggles sit in it too,
                // and focus landing on one of those was taken for having reached Install.
                var actionsHaveFocus by remember { mutableStateOf(false) }
                val bringIntoViewSpec = rememberTvLeftPaneBringIntoViewSpec(pageScroll) { headerHasFocus }

                // Focusing the action button can bring-into-view scroll the column down a touch, clipping the
                // header; the button is above the fold anyway, so snap back to the top after each landing.
                TvKeepFocusOnMainAction(
                    primary = primaryFocus,
                    actionHasFocus = { actionsHaveFocus },
                    userInteracted = { userInteracted },
                    onLanded = { runCatching { pageScroll.scrollTo(0) } },
                )

                // Scoped to just this screen: when the setting is on and a colour has been sampled from the
                // app's own icon, it overrides the accent for every MaterialTheme.colorScheme.primary use
                // inside (e.g. the favourite heart, the primary action button), without touching the app-wide
                // theme.
                ScopedAccentColor(if (accentMatchesAppIcon) iconAccent.accent else null) {
                    TvAppDetailPanes(
                        leftScroll = pageScroll,
                        leftBringIntoViewSpec = bringIntoViewSpec,
                        // Any key press marks startup focus as settled, so it's never re-stolen (see above).
                        onKeyDown = { userInteracted = true },
                        rightEntry = screenshotsFocus.takeIf { screenshots.isNotEmpty() },
                        left = {
                            Column(
                                modifier = Modifier.focusGroup().onFocusChanged { headerHasFocus = it.hasFocus },
                                verticalArrangement = spacedBy(20.dp),
                            ) {
                                // Header: icon + name + author + meta chips.
                                Row(horizontalArrangement = spacedBy(20.dp)) {
                                    AppMinimalIcon(
                                        app = appMinimal,
                                        isInstalled = isInstalled,
                                        modifier = Modifier.size(96.dp).clip(RoundedCornerShape(20.dp)),
                                        onIconBitmap = iconAccent.onIconBitmap,
                                    )
                                    Column(verticalArrangement = spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = app.metadata.name,
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        app.author?.name?.takeIf { it.isNotBlank() }?.let {
                                            Text(
                                                text = stringResource(R.string.by_author_FORMAT, it),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (summary.isNotBlank()) {
                                            AppTagline(
                                                text = descriptionTranslation.summaryOr(summary),
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                        }
                                        if (capabilities.any) CapabilityBadges(capabilities)
                                    }
                                }
                                // Under the icon, across the card: beside it, the column was too narrow for
                                // two of them and each landed on a line of its own.
                                TvMetaChips(
                                    category = app.categories.firstOrNull(),
                                    license = app.metadata.license.takeIf { it.isNotBlank() },
                                )
                                // Version and size as the phone's card shows them, not as tags.
                                HeroStatsRow(
                                    // Of the build Omnify would install, like the size beside it.
                                    version = (
                                        installablePackage?.manifest?.versionName
                                            ?: app.metadata.suggestedVersionName
                                        ).takeIf { it.isNotBlank() },
                                    size = installablePackage?.apk?.size?.toString(),
                                    onSourceCodeClick = null,
                                )
                                sourceNote?.let { note ->
                                    Text(
                                        text = note,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                // Action buttons (Install/Update/Launch + Uninstall) sharing the pane's width,
                                // then the favourite and hide toggles, discreet since they are rarely used.
                                Box(modifier = Modifier.onFocusChanged { actionsHaveFocus = it.hasFocus }) {
                                    TvPrimaryActions(
                                        viewModel = viewModel,
                                        updater = updater,
                                        externalViewModel = externalViewModel,
                                        externalUpdate = externalUpdate != null,
                                        packageName = app.metadata.packageName.name,
                                        isInstalled = isInstalled,
                                        updateAvailable = updateAvailable,
                                        installState = installState,
                                        downloadStatus = { downloadStatus },
                                        installConfirmed = installConfirmed,
                                        canUninstall = installedInfo?.canBeUninstalled != false,
                                        primaryActionFocusRequester = primaryFocus,
                                    )
                                }
                                TvSecondaryActions(
                                    isFavourite = isFavourite,
                                    onToggleFavourite = viewModel::toggleFavourite,
                                    isHidden = isHidden,
                                    onToggleHidden = viewModel::toggleHidden,
                                    onManageClick = if (isInstalled) {
                                        { context.openAppInfo(app.metadata.packageName.name) }
                                    } else {
                                        null
                                    },
                                    translation = descriptionTranslation.takeIf {
                                        translationEnabled && (summary.isNotBlank() || descriptionHtml.isNotBlank())
                                    },
                                    onTranslate = {
                                        viewModel.translateDescription(
                                            summary = summary,
                                            descriptionHtml = descriptionHtml,
                                            whatsNew = "",
                                        )
                                    },
                                    onShowOriginal = viewModel::showOriginalDescription,
                                    onChangelogClick = linkedChangelog?.let { linked ->
                                        {
                                            showChangelog = true
                                            viewModel.openChangelog(linked)
                                        }
                                    },
                                )
                            }

                            if (permissions.isNotEmpty()) PermissionsSection(permissions = permissions)
                            if (supportedLanguages.codes.isNotEmpty()) {
                                SupportedLanguagesSection(languages = supportedLanguages)
                            }
                            if (installedSignerRaw != null || !expectedSigners.isNullOrEmpty()) {
                                CertificateSection(
                                    installedSigner = installedSignerRaw,
                                    expectedSigners = expectedSigners,
                                )
                            }

                            if (packages.isNotEmpty()) {
                                TvVersionsTitle(
                                    mode = current.prereleases,
                                    onModeChange = viewModel::setPrereleases.takeIf { current.hasPrereleaseChoice },
                                )
                                TvAppSourcesVersions(
                                    catalogue = viewModel,
                                    external = externalViewModel,
                                    openOn = null,
                                    fallback = {},
                                )
                            }
                        },
                        right = {
                            if (screenshots.isNotEmpty()) {
                                TvScreenshots(screenshots, screenshotsFocus, landscape = tvScreenshots.isNotEmpty())
                            }
                            TvSectionTitle(stringResource(R.string.description))
                            if (descriptionHtml.isBlank()) {
                                TvDescriptionMessage(stringResource(R.string.no_description_available_DESC))
                            } else {
                                TvDescriptionPane(
                                    html = (descriptionTranslation as? DescriptionTranslation.Translated)
                                        ?.description?.takeIf { it.isNotBlank() } ?: descriptionHtml,
                                    baseUrl = "",
                                    javaScriptEnabled = false,
                                    webUrl = app.links?.webSite ?: app.links?.sourceCode ?: "",
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    upTarget = screenshotsFocus.takeIf { screenshots.isNotEmpty() },
                                    leftTarget = primaryFocus,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** [PrimaryActions] reading [downloadStatus] itself, so a download's progress ticks recompose only
 *  these buttons instead of the whole pane around them. With a tracked source taking the app's
 *  updates ([updater]), its update ([externalUpdate]) and its download are the ones shown. */
@Composable
private fun TvPrimaryActions(
    viewModel: AppDetailViewModel,
    updater: ExternalApp?,
    externalViewModel: ExternalAppsViewModel?,
    externalUpdate: Boolean,
    packageName: String,
    isInstalled: Boolean,
    updateAvailable: Boolean,
    installState: InstallState?,
    downloadStatus: () -> DownloadStatus?,
    installConfirmed: Boolean,
    canUninstall: Boolean,
    primaryActionFocusRequester: FocusRequester,
) {
    val tracked = updater?.let { app -> externalViewModel?.let { it to app } }
    val externalDownload = tracked?.let { (external, app) ->
        external.downloads.collectAsStateWithLifecycle().value[app.key]
    }
    val ownDownload = downloadStatus()
    PrimaryActions(
        packageName = packageName,
        isInstalled = isInstalled,
        updateAvailable = updateAvailable,
        installState = installState,
        downloadStatus = ownDownload ?: externalDownload,
        installConfirmed = installConfirmed,
        onInstallOrUpdate = if (externalUpdate && tracked != null) {
            { tracked.first.installOrUpdate(tracked.second) }
        } else {
            viewModel::installOrUpdate
        },
        onLaunch = viewModel::launch,
        onUninstall = if (canUninstall) viewModel::uninstall else null,
        onCancel = if (ownDownload == null && externalDownload != null && tracked != null) {
            { tracked.first.cancel(tracked.second) }
        } else {
            viewModel::cancel
        },
        primaryActionFocusRequester = primaryActionFocusRequester,
        fillWidth = true,
    )
}

/**
 * A TV screen header: a large title tinted with the theme accent. Used in place of the phone's solid
 * accent app-bar on the TV settings screens so they match the rest of the TV UI while still nodding to
 * the accent colour. There is no back button: a remote has its own back key.
 */
@Composable
internal fun TvAccentHeader(title: String, modifier: Modifier = Modifier) {
    // Transparent (no solid fill) so the screen's accent background flows continuously behind it — a
    // solid header band left a hard seam where it met the wash.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = TvOverscan + 8.dp, end = TvOverscan, top = TvOverscan, bottom = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TvMetaChips(category: String?, license: String?) {
    FlowRow(horizontalArrangement = spacedBy(8.dp), verticalArrangement = spacedBy(8.dp)) {
        category?.let { TvChip(it) }
        license?.let { TvChip(it) }
    }
}

@Composable
internal fun TvChip(
    text: String,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = content,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** The favourite and hide toggles, side by side and discreet (text buttons, muted): they are rarely
 *  used on an app's page, so they must not compete with the install button. Shared by both TV detail
 *  screens. */
@Composable
internal fun TvSecondaryActions(
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    isHidden: Boolean,
    onToggleHidden: () -> Unit,
    // Android's own page for the installed app (permissions, storage, notifications…); null hides it.
    onManageClick: (() -> Unit)?,
    // The description's translation, null when there is nothing to translate or no engine is set.
    translation: DescriptionTranslation?,
    onTranslate: () -> Unit,
    onShowOriginal: () -> Unit,
    // An external source's page: opens the catalogue page of the same app. Null hides it.
    onOtherSourcesClick: (() -> Unit)? = null,
    // Opens the app's changelog. Null hides it.
    onChangelogClick: (() -> Unit)? = null,
) {
    FlowRow(
        horizontalArrangement = spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        TvSecondaryAction(
            icon = Icons.Filled.Favorite,
            label = stringResource(R.string.favourites),
            active = isFavourite,
            onClick = onToggleFavourite,
        )
        TvSecondaryAction(
            icon = if (isHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            label = stringResource(if (isHidden) R.string.unhide_app else R.string.hide_app),
            active = isHidden,
            onClick = onToggleHidden,
        )
        onManageClick?.let { onClick ->
            TvSecondaryAction(
                icon = Icons.Filled.Settings,
                label = stringResource(R.string.manage),
                active = false,
                onClick = onClick,
            )
        }
        onChangelogClick?.let { onClick ->
            TvSecondaryAction(
                icon = Icons.Filled.History,
                label = stringResource(R.string.changelog),
                active = false,
                onClick = onClick,
            )
        }
        onOtherSourcesClick?.let { onClick ->
            TvSecondaryAction(
                icon = Icons.Filled.SwapHoriz,
                label = stringResource(R.string.other_sources),
                active = false,
                onClick = onClick,
            )
        }
        translation?.let { state ->
            // The same button in every state, so the focus stays on it while translating.
            val translated = state is DescriptionTranslation.Translated
            TvSecondaryAction(
                icon = Icons.Filled.Translate,
                label = stringResource(
                    when {
                        state == DescriptionTranslation.Loading -> R.string.translating
                        translated -> R.string.show_original
                        else -> R.string.translate
                    },
                ),
                active = translated,
                onClick = {
                    when {
                        state == DescriptionTranslation.Loading -> Unit
                        translated -> onShowOriginal()
                        else -> onTranslate()
                    }
                },
            )
        }
    }
}

/** Accent icon when on, muted when off. Also the repository page's edit and delete. */
@Composable
internal fun TvSecondaryAction(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(44.dp).tvFocusScale(1.05f).tvBringIntoViewOnFocus(),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * An app's version list with a pill per source offering it ([TvSourceVersions]), read from its catalogue
 * side ([catalogue]) and its tracked sources ([external]): on the app's own TV page, and on a tracked
 * source's, opened on that source ([openOn]). [fallback] stands in until the catalogue's side is read.
 */
@Composable
internal fun TvAppSourcesVersions(
    catalogue: AppDetailViewModel,
    external: ExternalAppsViewModel?,
    openOn: FollowedSource?,
    fallback: @Composable () -> Unit,
) {
    val state by catalogue.state.collectAsStateWithLifecycle()
    val success = state as? AppDetailState.Success
    if (success == null) {
        fallback()
        return
    }
    val packages = success.packages
    val app = success.app
    val installedInfo by catalogue.installedInfo.collectAsStateWithLifecycle()
    val externalSources by catalogue.externalSources.collectAsStateWithLifecycle()
    val updateSource by catalogue.updateSource.collectAsStateWithLifecycle()
    val updater by catalogue.externalUpdater.collectAsStateWithLifecycle()
    val installableRepo = remember(packages, app.metadata.suggestedVersionCode, installedInfo?.signer) {
        packages.automaticBuild(app.metadata.suggestedVersionCode, installedInfo?.signer)?.second
    }
    // Tapping a version confirms, then installs that exact release: the same engine
    // (catalogue.installVersion) and confirm/downgrade flow the phone screen uses.
    val versionInstall = rememberVersionInstallState()
    VersionInstallDialogs(
        state = versionInstall,
        installedInfo = installedInfo,
        onInstallVersion = catalogue::installVersion,
        onUninstall = catalogue::uninstall,
    )
    val updatingSource = updatingSourceOf(installedInfo != null, updater, updateSource.followed, installableRepo)
    val updatingName = sourceNameOf(updatingSource, packages, externalSources + listOfNotNull(updater))
    val followState = rememberSourceFollowState()
    SourceFollowDialogs(
        state = followState,
        appName = app.metadata.name,
        installedInfo = installedInfo,
        packages = packages,
        externalViewModel = external,
        onFollow = catalogue::follow,
        onInstallVersion = catalogue::installVersion,
        onUninstall = catalogue::uninstall,
    )
    val tabs = remember(packages, externalSources) { sourceTabs(packages, externalSources) }
    TvSourceVersions(
        tabs = tabs,
        updating = updatingSource,
        pinned = updateSource.followed != null,
        onFollowTab = followState.followTab(installedInfo, packages, updatingName),
        defaultSource = openOn ?: updatingSource ?: installableRepo?.let { FollowedSource.Repository(it.id) },
        onSelectRepo = catalogue::setPreferredRepo,
    ) { tab ->
        val source = tab.external
        if (source != null) {
            external?.let { TvExternalSourceReleases(source, it) }
        } else {
            val repoId = (tab.source as? FollowedSource.Repository)?.repoId
            TvVersionsList(
                packages = packages.filter { it.second.id == repoId },
                suggestedVersionCode = app.metadata.suggestedVersionCode,
                onVersionClick = { pkg, repo -> versionInstall.version = pkg to repo },
            )
        }
    }
}

/**
 * The TV page's version list, with one pill per source above it when more than one offers the app: the
 * phone page's tabs in the TV's own shapes, picked with the remote's centre key, a star on the source
 * the updates come from. Under them, the chip or the button that follows the source on screen
 * ([onFollowTab], null while the app isn't installed), then that source's versions ([content]).
 */
@Composable
private fun TvSourceVersions(
    tabs: List<SourceTab>,
    updating: FollowedSource?,
    pinned: Boolean,
    onFollowTab: ((SourceTab) -> Unit)?,
    defaultSource: FollowedSource?,
    onSelectRepo: (Int) -> Unit,
    content: @Composable (SourceTab) -> Unit,
) {
    var pickedSource by remember { mutableStateOf<FollowedSource?>(null) }
    val selected = tabs.firstOrNull { it.source == pickedSource }
        ?: tabs.firstOrNull { it.source == defaultSource }
        ?: tabs.firstOrNull()
        ?: return
    // The Install button follows the repository on screen, unless one is followed for updates.
    LaunchedEffect(selected.source) {
        (selected.source as? FollowedSource.Repository)?.let { onSelectRepo(it.repoId) }
    }
    if (tabs.size > 1) {
        FlowRow(horizontalArrangement = spacedBy(8.dp), verticalArrangement = spacedBy(8.dp)) {
            tabs.forEach { tab ->
                TvSourcePill(
                    tab = tab,
                    selected = tab == selected,
                    updating = tab.source == updating,
                    onClick = { pickedSource = tab.source },
                )
            }
        }
    }
    AnimatedContent(
        targetState = selected,
        contentKey = { it.source },
        transitionSpec = {
            fadeIn(tween(SOURCE_SWITCH_MS - TV_SOURCE_LEAVE_MS, TV_SOURCE_LEAVE_MS, EmphasizedDecelerate)) togetherWith
                fadeOut(tween(TV_SOURCE_LEAVE_MS, easing = EmphasizedAccelerate)) using SizeTransform(clip = false)
        },
        label = "tvVersionSource",
    ) { tab ->
        Column(verticalArrangement = spacedBy(12.dp)) {
            if (tabs.size > 1 && onFollowTab != null) {
                FollowSourceRow(
                    updating = tab.source == updating,
                    pinned = pinned,
                    onFollow = { onFollowTab(tab) },
                    modifier = Modifier.tvBringIntoViewOnFocus(),
                )
            }
            content(tab)
        }
    }
}

/** How long a source's version list takes to fade before another one comes in. */
private const val TV_SOURCE_LEAVE_MS = 90

/** One source of [TvSourceVersions]: its name over the version it offers, filled when on screen. */
@Composable
private fun TvSourcePill(tab: SourceTab, selected: Boolean, updating: Boolean, onClick: () -> Unit) {
    // A ring as well as the scale: on a filled pill, the scale alone hardly shows where the remote is.
    var focused by remember { mutableStateOf(false) }
    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = if (focused) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null,
        modifier = Modifier
            .onFocusChanged { focused = it.hasFocus }
            .tvFocusScale(1.05f)
            .tvBringIntoViewOnFocus(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = spacedBy(4.dp)) {
                if (updating) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = stringResource(R.string.source_followed),
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(tab.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
            tab.version?.let { version ->
                Text(version, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

/**
 * The catalogue app's version list — the exact same engine as the phone screen (multi-ABI variants
 * collapsed to one row per versionName via [selectForDevice]; the device-suggested build highlighted)
 * and the exact same [PackageItem] rows, so a version reads identically to the phone build. Only the
 * surrounding TV layout differs.
 */
@Composable
private fun TvVersionsList(
    packages: List<Pair<Package, Repo>>,
    suggestedVersionCode: Long,
    onVersionClick: (Package, Repo) -> Unit,
) {
    // Multi-ABI apps ship one APK per architecture under the same versionName; collapse to one row per
    // versionName keeping the build this device can install, newest first — mirrors the phone screen.
    val shownPackages = remember(packages) {
        packages
            .groupBy { it.first.manifest.versionName }
            .values
            .mapNotNull { variants -> variants.selectForDevice(Long.MAX_VALUE) }
            .sortedByDescending { it.first.manifest.versionCode }
    }
    val suggestedVersion = remember(shownPackages, suggestedVersionCode) {
        shownPackages.selectForDevice(suggestedVersionCode)?.first?.manifest?.versionCode
    }
    Column(verticalArrangement = spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        shownPackages.take(MAX_VERSIONS_SHOWN).forEach { (pkg, repo) ->
            val isSuggested = suggestedVersion != null && pkg.manifest.versionCode == suggestedVersion
            PackageItem(
                item = pkg,
                repo = repo,
                onClick = { onVersionClick(pkg, repo) },
                onLongClick = {},
                highlighted = isSuggested,
                // Several chips can apply at once (the installed version is also the suggested one).
                label = if (isSuggested || pkg.installed || pkg.isPrerelease) {
                    {
                        if (isSuggested) {
                            TvVersionChip(
                                text = stringResource(R.string.suggested),
                                container = MaterialTheme.colorScheme.tertiaryContainer,
                                content = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                        if (pkg.installed) {
                            TvVersionChip(
                                text = stringResource(R.string.installed),
                                container = MaterialTheme.colorScheme.secondaryContainer,
                                content = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        if (pkg.isPrerelease) {
                            TvVersionChip(
                                text = stringResource(R.string.external_prerelease_label),
                                container = MaterialTheme.colorScheme.errorContainer,
                                content = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun TvVersionChip(text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier = Modifier
            .background(container, shape = androidx.compose.foundation.shape.CircleShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/**
 * The "Versions" title of an app's page, with its pre-release choice ([mode]) at its end when it can be
 * changed there ([onModeChange]).
 */
@Composable
internal fun TvVersionsTitle(mode: PrereleaseMode, onModeChange: ((PrereleaseMode) -> Unit)?) {
    // On one baseline: a choice wrapping onto two lines starts level with the title.
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.alignByBaseline()) {
            TvSectionTitle(stringResource(R.string.versions))
        }
        // The title keeps its own width: a long choice wraps beside it instead.
        Box(
            modifier = Modifier.weight(1f).alignByBaseline(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (onModeChange != null) {
                PrereleaseModeChooser(
                    mode = mode,
                    onModeChange = onModeChange,
                    textStyle = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun TvSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
internal fun TvCentered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

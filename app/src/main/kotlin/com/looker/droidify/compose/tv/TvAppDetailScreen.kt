package com.looker.droidify.compose.tv

import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.ui.platform.LocalContext
import com.looker.droidify.compose.appDetail.PermissionsSection
import com.looker.droidify.compose.appDetail.isRootCompatible
import com.looker.droidify.compose.components.RootBadge
import com.looker.droidify.compose.components.SupportedLanguagesSection
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
import com.looker.droidify.compose.appDetail.PrimaryActions
import com.looker.droidify.compose.appList.AppMinimalIcon
import com.looker.droidify.compose.appDetail.components.PackageItem
import com.looker.droidify.compose.components.InstallConflictDialog
import com.looker.droidify.compose.components.InstallVersionDialog
import com.looker.droidify.data.catalogueBuildIsOlder
import com.looker.droidify.data.model.Package
import com.looker.droidify.data.model.Repo
import com.looker.droidify.installer.model.InstallState
import androidx.compose.foundation.layout.Arrangement.spacedBy
import com.looker.droidify.compose.components.CertificateSection
import com.looker.droidify.compose.components.DescriptionTranslation
import com.looker.droidify.compose.components.HeroStatsRow
import com.looker.droidify.compose.components.TvOverscan
import com.looker.droidify.compose.components.tvBringIntoViewOnFocus
import com.looker.droidify.compose.components.tvFocusScale
import com.looker.droidify.compose.theme.ScopedAccentColor
import com.looker.droidify.data.model.minimal
import com.looker.droidify.data.model.selectForDevice
import com.looker.droidify.utility.common.rememberIconAccent

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
            is AppDetailState.Error -> TvCentered {
                Text(current.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                val installable = remember(packages, app.metadata.suggestedVersionCode) {
                    packages.selectForDevice(app.metadata.suggestedVersionCode)
                }
                val installablePackage = installable?.first
                // The certificate(s) the repository index declares for the version we'd actually install/
                // update to, known the moment the catalogue is synced, whether or not the app is installed
                // yet, unlike [installedSignerRaw].
                val expectedSigners = installablePackage?.manifest?.signer
                // The same lookups the phone page makes, so both show the same things.
                val suggestedPackage = installablePackage ?: packages.firstOrNull()?.first
                val permissions = remember(suggestedPackage) { suggestedPackage?.manifest?.permissions.orEmpty() }
                val isRootCompatible = remember(app, installablePackage, installedPackage) {
                    app.isRootCompatible(installablePackage ?: installedPackage)
                }
                // externallySourced: mirrors the phone screen, see AppDetailViewModel's own property. A copy
                // a tracked external source is responsible for is not this catalogue entry's to update, and
                // the comparison below says nothing about which build is newer across two numberings.
                val updateAvailable = !externallySourced && installedVersionCode != null &&
                    installablePackage != null &&
                    !catalogueBuildIsOlder(installedInfo?.version, installablePackage.manifest.versionName) &&
                    installedVersionCode < installablePackage.manifest.versionCode
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
                // Only the description is shown here, so only it is translated (no summary, no what's new).
                LaunchedEffect(app.metadata.packageName.name) {
                    viewModel.maybeAutoTranslate(summary = "", descriptionHtml = descriptionHtml, whatsNew = "")
                }
                // Where "up" from the description goes: back to the screenshots above it.
                val screenshotsFocus = remember { FocusRequester() }

                // Tapping a version confirms, then installs that exact release — the same engine
                // (viewModel.installVersion) and confirm/downgrade flow the phone screen uses.
                var versionToInstall by remember(app.metadata.packageName.name) {
                    mutableStateOf<Pair<Package, Repo>?>(null)
                }
                // A downgrade the user confirmed: kept while the current app uninstalls, then installed once
                // it's gone (Android blocks in-place downgrades) — mirrors the phone screen.
                var pendingDowngradeInstall by remember(app.metadata.packageName.name) {
                    mutableStateOf<Pair<Package, Repo>?>(null)
                }
                LaunchedEffect(installedInfo, pendingDowngradeInstall) {
                    val pending = pendingDowngradeInstall ?: return@LaunchedEffect
                    if (installedInfo == null) {
                        viewModel.installVersion(pending.first, pending.second)
                        pendingDowngradeInstall = null
                    }
                }
                versionToInstall?.let { (pkg, repo) ->
                    InstallVersionDialog(
                        versionName = pkg.manifest.versionName,
                        isDowngrade = installedVersionCode != null &&
                            pkg.manifest.versionCode < installedVersionCode,
                        onInstall = {
                            viewModel.installVersion(pkg, repo)
                            versionToInstall = null
                        },
                        onUninstall = {
                            pendingDowngradeInstall = pkg to repo
                            viewModel.uninstall()
                            versionToInstall = null
                        },
                        onDismiss = { versionToInstall = null },
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
                                        if (isRootCompatible) RootBadge()
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
                                    version = app.metadata.suggestedVersionName.takeIf { it.isNotBlank() },
                                    size = installablePackage?.apk?.size?.toString(),
                                    onSourceCodeClick = null,
                                )

                                // Action buttons (Install/Update/Launch + Uninstall) sharing the pane's width,
                                // then the favourite and hide toggles, discreet since they are rarely used.
                                Box(modifier = Modifier.onFocusChanged { actionsHaveFocus = it.hasFocus }) {
                                    TvPrimaryActions(
                                        viewModel = viewModel,
                                        packageName = app.metadata.packageName.name,
                                        isInstalled = isInstalled,
                                        updateAvailable = updateAvailable,
                                        installState = installState,
                                        downloadStatus = { downloadStatus },
                                        installConfirmed = installConfirmed,
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
                                        translationEnabled && descriptionHtml.isNotBlank()
                                    },
                                    onTranslate = {
                                        viewModel.translateDescription(
                                            summary = "",
                                            descriptionHtml = descriptionHtml,
                                            whatsNew = "",
                                        )
                                    },
                                    onShowOriginal = viewModel::showOriginalDescription,
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
                                TvSectionTitle(stringResource(R.string.versions))
                                TvVersionsList(
                                    packages = packages,
                                    suggestedVersionCode = app.metadata.suggestedVersionCode,
                                    onVersionClick = { pkg, repo -> versionToInstall = pkg to repo },
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
 *  these buttons instead of the whole pane around them. */
@Composable
private fun TvPrimaryActions(
    viewModel: AppDetailViewModel,
    packageName: String,
    isInstalled: Boolean,
    updateAvailable: Boolean,
    installState: InstallState?,
    downloadStatus: () -> DownloadStatus?,
    installConfirmed: Boolean,
    primaryActionFocusRequester: FocusRequester,
) {
    PrimaryActions(
        packageName = packageName,
        isInstalled = isInstalled,
        updateAvailable = updateAvailable,
        installState = installState,
        downloadStatus = downloadStatus(),
        installConfirmed = installConfirmed,
        onInstallOrUpdate = viewModel::installOrUpdate,
        onLaunch = viewModel::launch,
        onUninstall = viewModel::uninstall,
        onCancel = viewModel::cancel,
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
        shownPackages.forEach { (pkg, repo) ->
            val isSuggested = suggestedVersion != null && pkg.manifest.versionCode == suggestedVersion
            PackageItem(
                item = pkg,
                repo = repo,
                onClick = { onVersionClick(pkg, repo) },
                onLongClick = {},
                highlighted = isSuggested,
                // Both chips can apply at once (the installed version is also the suggested one).
                label = if (isSuggested || pkg.installed) {
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

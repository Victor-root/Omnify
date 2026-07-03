package com.looker.droidify.compose.appDetail

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import com.looker.droidify.compose.components.DescriptionTranslation
import com.looker.droidify.compose.components.DownloadProgressRow
import com.looker.droidify.compose.components.InstallingRow
import com.looker.droidify.compose.components.TranslateAction
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
import com.looker.droidify.utility.text.toAnnotatedString
import com.looker.droidify.compose.theme.AccentBarHeight
import com.looker.droidify.compose.theme.accentTopAppBarColors
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Maximum number of version rows rendered on the detail screen. The screen is a single
 * (non-lazy) scroll column, so every row composes eagerly; a handful of apps ship hundreds
 * of historical releases, which froze the UI. The list is sorted newest-first, so the cap
 * keeps the releases users actually care about.
 */
private const val MAX_VERSIONS_SHOWN = 50

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
    val isFavourite by viewModel.isFavourite.collectAsStateWithLifecycle()
    val installedInfo by viewModel.installedInfo.collectAsStateWithLifecycle()
    val descriptionTranslation by viewModel.descriptionTranslation.collectAsStateWithLifecycle()
    val translationEnabled by viewModel.translationEnabled.collectAsStateWithLifecycle()
    val supportedLanguages by viewModel.supportedLanguages.collectAsStateWithLifecycle()
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
    val signatureConflict by viewModel.signatureConflict.collectAsStateWithLifecycle()

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
    // leave the user stuck in the header; it now drops onto that button. No effect with touch.
    val primaryActionFocusRequester = remember { FocusRequester() }
    val isTelevision = LocalIsTelevision.current
    // TV: open the detail with focus already on the main action instead of the back arrow, retrying
    // briefly until the button is laid out. Keyed on the loaded app so it runs once per app. No-op on touch.
    val successPackageName = successState?.app?.metadata?.packageName?.name
    if (isTelevision) {
        LaunchedEffect(successPackageName) {
            if (successPackageName != null) {
                repeat(20) {
                    if (runCatching { primaryActionFocusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
                    delay(50)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = accentTopAppBarColors(),
                expandedHeight = AccentBarHeight,
                modifier = Modifier.onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        runCatching { primaryActionFocusRequester.requestFocus() }.isSuccess
                    } else {
                        false
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
    ) { padding ->
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
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

/**
 * Confirms installing a specific version the user tapped in the versions list. For a normal case it
 * offers Install; for a downgrade (an older version while a newer one is installed) it explains Android
 * won't replace it in place and offers to uninstall the current version first.
 */
@Composable
private fun InstallVersionDialog(
    versionName: String,
    isDowngrade: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.install_version_FORMAT, versionName)) },
        text = if (isDowngrade) {
            { Text(stringResource(R.string.install_version_downgrade_DESC)) }
        } else {
            null
        },
        confirmButton = {
            if (isDowngrade) {
                TextButton(onClick = onUninstall) { Text(stringResource(R.string.uninstall)) }
            } else {
                TextButton(onClick = onInstall) { Text(stringResource(R.string.install)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun PrimaryActions(
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
        )

        installing -> InstallingRow(
            onCancel = onCancel,
            modifier = modifier.fillMaxWidth(),
        )

        else -> Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                if (isTelevision) 24.dp else 8.dp,
                if (isTelevision) Alignment.CenterHorizontally else Alignment.Start,
            ),
        ) {
            // Big and centred on TV; full-width (weight) on touch. The primary action also holds the
            // startup focus (see primaryActionFocusRequester).
            val tvPrimaryButton = if (isTelevision) {
                Modifier.height(60.dp).widthIn(min = 340.dp)
            } else {
                Modifier.weight(1f)
            }.focusRequester(primaryActionFocusRequester)
            val tvSecondaryButton = if (isTelevision) Modifier.height(60.dp).widthIn(min = 200.dp) else Modifier
            when {
                !isInstalled -> Button(
                    onClick = onInstallOrUpdate,
                    modifier = tvPrimaryButton.tvFocusScale(1.10f),
                ) { Text(stringResource(R.string.install)) }

                updateAvailable -> Button(
                    onClick = onInstallOrUpdate,
                    modifier = tvPrimaryButton.tvFocusScale(1.10f),
                ) { Text(stringResource(R.string.update)) }

                else -> Button(
                    onClick = onLaunch,
                    modifier = tvPrimaryButton.tvFocusScale(1.10f),
                ) { Text(stringResource(R.string.launch)) }
            }
            if (isInstalled) {
                OutlinedButton(
                    onClick = onUninstall,
                    modifier = tvSecondaryButton.tvFocusScale(1.10f),
                ) {
                    Text(stringResource(R.string.uninstall))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDetail(
    app: App,
    packages: List<Pair<Package, Repo>>,
    customButtons: List<CustomButton>,
    installState: InstallState?,
    downloadStatus: DownloadStatus?,
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
    modifier: Modifier = Modifier,
) {
    val installedPackage = app.packages?.firstOrNull { it.installed }
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // A focus group so D-pad navigation stays scoped to the content (the action button itself is
            // the explicit focus target, see primaryActionFocusRequester).
            .focusGroup()
            .then(modifier)
            // Breathing room so the header section isn't glued under the top bar.
            .padding(top = 16.dp),
    ) {
        HeaderSection(
            app = app,
            packageName = app.metadata.packageName.name,
            isInstalled = app.packages?.any { it.installed } == true,
            isFavorite = isFavourite,
            onToggleFavorite = onToggleFavourite,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        PrimaryActions(
            isInstalled = installedPackage != null,
            updateAvailable = updateAvailable,
            installState = installState,
            downloadStatus = downloadStatus,
            onInstallOrUpdate = onInstallOrUpdate,
            onLaunch = onLaunch,
            onUninstall = onUninstall,
            onCancel = onCancel,
            primaryActionFocusRequester = primaryActionFocusRequester,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // The real installed version + its source, so it's clear which build is on the device (e.g. a
        // fork installed over the upstream package keeps its own version).
        installedInfo?.let { info ->
            Text(
                text = stringResource(
                    R.string.installed_version_source,
                    info.version,
                    info.source,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        // Flag apps that embed Google Play services so it's obvious up-front they may misbehave on a
        // de-Googled device (no GMS / microG). Detected from the manifest permissions, which is far
        // more reliable than the broad "non-free dependency" anti-feature tag.
        val suggestedPermissions =
            (installablePackage ?: packages.firstOrNull()?.first)?.manifest?.permissions.orEmpty()
        if (suggestedPermissions.requiresGoogleServices()) {
            Spacer(modifier = Modifier.height(8.dp))
            GoogleServicesBadge(modifier = Modifier.padding(horizontal = 16.dp))
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
            Spacer(modifier = Modifier.height(8.dp))
            val handler = LocalUriHandler.current
            // Parsing the HTML description is expensive; do it once per description instead of on
            // every recomposition (the detail screen recomposes repeatedly while data loads).
            val description = remember(app.metadata.description) {
                app.metadata.description.toAnnotatedString(onUrlClick = { handler.openUri(it) })
            }
            // Show the translation when one is ready (the toggle lives in the top bar); otherwise the
            // original formatted text.
            val translated = descriptionTranslation as? DescriptionTranslation.Translated
            if (translated != null && translated.description.isNotBlank()) {
                Text(
                    text = translated.description,
                    style = MaterialTheme.typography.bodyMedium,
                    // TV: a D-pad focus stop so the remote can land on the description and scroll it into
                    // view instead of jumping over it to the buttons below. No-op on touch.
                    modifier = Modifier.padding(horizontal = 16.dp).tvReadable(),
                )
            } else {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp).tvReadable(),
                )
            }
        }
        val suggestedPackage = installablePackage ?: packages.firstOrNull()?.first

        suggestedPackage?.whatsNew?.takeIf { it.isNotBlank() }?.let { whatsNew ->
            Spacer(modifier = Modifier.height(16.dp))
            val translatedWhatsNew = (descriptionTranslation as? DescriptionTranslation.Translated)
                ?.whatsNew?.takeIf { it.isNotBlank() }
            WhatsNewSection(whatsNew = translatedWhatsNew ?: whatsNew)
        }

        if (app.hasLinks()) {
            Spacer(modifier = Modifier.height(8.dp))
            LinksSection(app = app)
        }

        val antiFeatures = suggestedPackage?.antiFeatures.orEmpty()
        if (antiFeatures.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            AntiFeaturesSection(antiFeatures = antiFeatures)
        }

        val permissions = suggestedPackage?.manifest?.permissions.orEmpty()
        if (permissions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            PermissionsSection(permissions = permissions)
        }

        if (supportedLanguages.codes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            SupportedLanguagesSection(languages = supportedLanguages)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Repos that offer this app. When more than one offers it (e.g. F-Droid + IzzyOnDroid), show
        // tabs so the user can pick which repo to install from; both the version list below and the
        // Install/Update button then follow the selected repo. Default to the repo that provides the
        // device-suggested version.
        val repos = remember(packages) { packages.map { it.second }.distinctBy { it.id } }
        var selectedRepoId by remember(repos, installableRepo) {
            mutableStateOf(installableRepo?.id ?: repos.firstOrNull()?.id)
        }
        LaunchedEffect(selectedRepoId) { selectedRepoId?.let(onSelectRepo) }
        if (repos.size > 1) {
            val selectedIndex = repos.indexOfFirst { it.id == selectedRepoId }.coerceAtLeast(0)
            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                edgePadding = 16.dp,
                modifier = Modifier.fillMaxWidth(),
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
        shownPackages.take(MAX_VERSIONS_SHOWN).forEach { (pkg, repo) ->
            val isSuggested = suggestedVersion != null && pkg.manifest.versionCode == suggestedVersion
            PackageItem(
                item = pkg,
                repo = repo,
                onClick = { versionToInstall = pkg to repo },
                onLongClick = {},
                backgroundColor = if (isSuggested) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surface
                },
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
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeaderSection(
    app: App?,
    packageName: String,
    isInstalled: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Reuse the list tile's icon logic so the header falls back the same way: repo icon, then the
        // repo's generic icon.png, then the installed app's own launcher icon, then a placeholder. Repos
        // like TwinHelix ship no icon in their index, so without the launcher-icon fallback the header
        // showed a blank placeholder even though the list (which has this fallback) showed the real logo.
        val minimal = app?.minimal()
        if (minimal != null) {
            AppMinimalIcon(
                app = minimal,
                isInstalled = isInstalled,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Image(
                painter = painterResource(id = R.drawable.ic_cannot_load),
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }

        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app?.metadata?.name ?: packageName,
                style = MaterialTheme.typography.titleLarge,
                overflow = TextOverflow.Ellipsis,
            )
            val version = app?.metadata?.suggestedVersionName?.takeIf { it.isNotBlank() } ?: ""
            if (version.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.version_FORMAT, version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = app?.author?.name ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Just the heart, no container (the tonal toggle button squashed it into an oval): a larger
        // filled red heart when favourited, a neutral outline when not.
        IconToggleButton(
            checked = isFavorite,
            onCheckedChange = { onToggleFavorite() },
            // TV: square so the focus halo is a clean circle, and the heart scales up on focus. No-op
            // on touch.
            modifier = (if (LocalIsTelevision.current) Modifier.size(48.dp) else Modifier)
                .tvFocusScale(),
        ) {
            Icon(
                painter = painterResource(
                    if (isFavorite) R.drawable.ic_favourite_checked else R.drawable.ic_favourite,
                ),
                contentDescription = stringResource(R.string.favourites),
                tint = if (isFavorite) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

@Composable
private fun WhatsNewSection(whatsNew: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.whats_new))
        Text(
            text = whatsNew,
            style = MaterialTheme.typography.bodyMedium,
            // TV: a D-pad focus stop so the remote can land here and scroll it into view. No-op on touch.
            modifier = Modifier.padding(horizontal = 16.dp).tvReadable(),
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
        SectionTitle(stringResource(R.string.links), R.drawable.ic_tabler_link)
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

@Composable
private fun LinkRow(
    @DrawableRes iconRes: Int,
    title: String,
    url: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // TV only: a soft green fill behind the focused row (a full-width row can't scale without
            // overflowing the screen). No-op on touch.
            .tvFocusFill(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Permission-name prefixes that mean the app embeds Google Play services and relies on them to run
 *  fully: the GMS APIs, Firebase/GCM push delivery, and the Google Services Framework. Restricted to
 *  these reliable markers so the badge doesn't fire on unrelated `com.google.android.*` permissions
 *  (e.g. a launcher's search-box or a DPC's setup-wizard permission). */
private val googleServicePermissionPrefixes = listOf(
    "com.google.android.gms.permission.",
    "com.google.android.c2dm.permission.RECEIVE",
    "com.google.android.providers.gsf.permission.READ_GSERVICES",
)

private fun List<Permission>.requiresGoogleServices(): Boolean =
    any { permission -> googleServicePermissionPrefixes.any(permission.name::startsWith) }

/** Up-front notice that the app needs Google Play services, so it's clear before installing that it
 *  may not work fully on a de-Googled device (no GMS / microG). */
@Composable
private fun GoogleServicesBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_tabler_brand_google),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column {
                Text(
                    text = stringResource(R.string.requires_google_services),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.requires_google_services_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
                .tvFocusFill(RoundedCornerShape(12.dp))
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
            Text(
                text = permissions.size.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun SectionTitle(title: String, @DrawableRes iconRes: Int? = null) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
        Text(text = title, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Collapsible "supported languages" section. When the data is reliable (read from the installed APK)
 * the header states whether the phone's language is translated ("Français: translated"); when it's only
 * the store-listing approximation (app not installed) it shows a caveat instead of asserting anything,
 * so it can't wrongly claim a language isn't translated. The full list expands below, device language
 * first and ticked.
 */
@Composable
private fun SupportedLanguagesSection(languages: SupportedLanguages) {
    var expanded by remember { mutableStateOf(false) }
    val deviceLocale = remember { Locale.getDefault() }
    val reliable = languages.fromInstalledApk
    val localeCodes = languages.codes
    val languageList = remember(localeCodes) {
        localeCodes
            .map { code ->
                val loc = Locale.forLanguageTag(code.replace('_', '-'))
                val display = loc.getDisplayName(loc)
                    .replaceFirstChar { it.uppercase(loc) }
                    .ifBlank { code }
                Triple(code, display, loc.language.isNotEmpty() && loc.language == deviceLocale.language)
            }
            // Device language first, then alphabetical by display name.
            .sortedWith(compareByDescending<Triple<String, String, Boolean>> { it.third }
                .thenBy { it.second.lowercase(deviceLocale) })
    }
    val deviceSupported = languageList.any { it.third }
    val deviceName = deviceLocale.getDisplayLanguage(deviceLocale)
        .replaceFirstChar { it.uppercase(deviceLocale) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .tvFocusFill(RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                // The same translate glyph as the top bar's Translate button, so the languages section
                // reads as "translations" at a glance.
                imageVector = Icons.Filled.Translate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.supported_languages),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (reliable) {
                    // Real UI languages from the installed APK: state it definitely.
                    Text(
                        text = stringResource(
                            if (deviceSupported) {
                                R.string.language_supported_FORMAT
                            } else {
                                R.string.language_not_supported_FORMAT
                            },
                            deviceName,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (deviceSupported) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                } else {
                    // Only the store listing: don't assert translated/not, just flag the approximation.
                    Text(
                        text = stringResource(R.string.language_from_store_listing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = languageList.size.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            languageList.forEach { (_, display, isDevice) ->
                // Only highlight/tick the device language when the data is reliable (installed APK), so
                // the store-listing approximation doesn't imply a certainty we don't have.
                val highlight = isDevice && reliable
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = display,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (highlight) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (highlight) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f)),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
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
                    .padding(8.dp),
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

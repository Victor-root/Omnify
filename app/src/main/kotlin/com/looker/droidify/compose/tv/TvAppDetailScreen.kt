package com.looker.droidify.compose.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.appDetail.AppDetailState
import com.looker.droidify.compose.appDetail.AppDetailViewModel
import com.looker.droidify.compose.appDetail.PrimaryActions
import com.looker.droidify.compose.appDetail.ScreenshotsRow
import com.looker.droidify.compose.appList.AppMinimalIcon
import com.looker.droidify.compose.appDetail.components.PackageItem
import com.looker.droidify.compose.components.InstallVersionDialog
import com.looker.droidify.data.model.Package
import com.looker.droidify.data.model.Repo
import androidx.compose.foundation.layout.Arrangement
import com.looker.droidify.compose.externalApps.ReadmeWebView
import com.looker.droidify.compose.externalApps.WebViewDialog
import com.looker.droidify.compose.components.TvOverscan
import com.looker.droidify.compose.components.tvBringIntoViewOnFocus
import com.looker.droidify.compose.components.tvFocusFill
import com.looker.droidify.compose.components.tvFocusScale
import com.looker.droidify.data.model.minimal
import com.looker.droidify.data.model.selectForDevice
import kotlinx.coroutines.delay

/**
 * The Android TV app-detail screen — a lean, couch-friendly presentation over the same
 * [AppDetailViewModel] the phone screen uses: icon + name + a few meta chips, one big Install / Update /
 * Launch button that holds the startup focus, a screenshots row, the description, and the version list.
 * The heavy install lifecycle is reused verbatim ([PrimaryActions]), so behaviour matches the phone
 * build exactly; only the layout is TV-specific. Never composed off TV.
 */
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
    val installedInfo by viewModel.installedInfo.collectAsStateWithLifecycle()

    BackHandler { onBackClick() }

    when (val current = state) {
        is AppDetailState.Loading -> TvCentered { CircularProgressIndicator() }
        is AppDetailState.Error -> TvCentered {
            Text(current.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        is AppDetailState.Success -> {
            val app = current.app
            val packages = current.packages

            val installedPackage = remember(packages) { packages.map { it.first }.firstOrNull { it.installed } }
            val isInstalled = installedPackage != null
            val installable = remember(packages, app.metadata.suggestedVersionCode) {
                packages.selectForDevice(app.metadata.suggestedVersionCode)
            }
            val installablePackage = installable?.first
            val updateAvailable = installedPackage != null && installablePackage != null &&
                installedPackage.manifest.versionCode < installablePackage.manifest.versionCode
            // Mirrors the phone screen: the OS reports Installed before the app's own installed-packages
            // table catches up, so hold the "installing" look until the confirmed version matches.
            val installConfirmed = downloadTargetVersionCode == null ||
                installedPackage?.manifest?.versionCode == downloadTargetVersionCode

            val primaryFocus = remember { FocusRequester() }
            // Hoisted so the startup focus burst below can pin the page back to the top: focusing the
            // action button nudges the scroll down a hair (bring-into-view), which was clipping the header.
            val pageScroll = rememberScrollState()
            // Once the user has pressed any key, focus is theirs — stop the startup burst below from
            // re-stealing it. Without this, installedInfo re-emitting (it changes on background catalogue/
            // network activity) re-ran the burst and yanked focus back to the action button every few
            // seconds while the user was browsing the page.
            var userInteracted by remember { mutableStateOf(false) }
            // Land focus on the main action button when the screen opens, retrying until it's laid out
            // (its node is swapped when installedInfo resolves and flips Install -> Launch). Skipped once
            // the user has taken over. Same intent as the phone TV path.
            LaunchedEffect(app.metadata.packageName.name, installedInfo) {
                if (userInteracted) return@LaunchedEffect
                repeat(20) {
                    if (runCatching { primaryFocus.requestFocus() }.getOrDefault(false)) {
                        // Focusing the action button can bring-into-view scroll the column down a touch,
                        // clipping the header; the button is above the fold anyway, so snap back to the
                        // top once (unless the user already grabbed the scroll).
                        delay(50)
                        if (!userInteracted) runCatching { pageScroll.scrollTo(0) }
                        return@LaunchedEffect
                    }
                    delay(50)
                }
            }

            val screenshots = remember(app.screenshots) {
                (app.screenshots?.tv?.takeIf { it.isNotEmpty() } ?: app.screenshots?.phone).orEmpty()
            }
            // The catalogue description is HTML; shown (rendered) in a full-screen reader on demand — see
            // TvOpenDescriptionButton — so it looks the same as an external app's README.
            val descriptionHtml = app.metadata.description.raw
            var showDescription by remember(app.metadata.packageName.name) { mutableStateOf(false) }
            // Screen height, so the README preview can fill from its top down to the bottom edge.
            var viewportPx by remember { mutableStateOf(0) }

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
                    isDowngrade = installedPackage != null &&
                        pkg.manifest.versionCode < installedPackage.manifest.versionCode,
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

            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TvAccentBackground()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportPx = it.height }
                    // Any key press marks startup focus as settled, so it's never re-stolen (see above).
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) userInteracted = true
                        false
                    }
                    // overscrollEffect = null: the description preview hosts a hardware-accelerated
                    // WebView, and Android 12+'s stretch overscroll crashes RenderThread when it redraws
                    // that WebView at the scroll boundary (same reason as the phone detail screen).
                    .verticalScroll(pageScroll, overscrollEffect = null)
                    .padding(horizontal = TvOverscan + 16.dp, vertical = TvOverscan),
                verticalArrangement = spacedBy(24.dp),
            ) {
                TvBackButton(onBackClick)

                // Header: icon + name + author + meta chips.
                Row(horizontalArrangement = spacedBy(24.dp)) {
                    AppMinimalIcon(
                        app = app.minimal(),
                        isInstalled = isInstalled,
                        modifier = Modifier.size(120.dp).clip(RoundedCornerShape(24.dp)),
                    )
                    Column(verticalArrangement = spacedBy(8.dp)) {
                        Text(
                            text = app.metadata.name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        app.author?.name?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = stringResource(R.string.by_author_FORMAT, it),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TvMetaChips(
                            category = app.categories.firstOrNull(),
                            version = app.metadata.suggestedVersionName.takeIf { it.isNotBlank() },
                            license = app.metadata.license.takeIf { it.isNotBlank() },
                        )
                    }
                }

                // Action buttons (Install/Update/Launch + Uninstall) with the favourite alongside them,
                // the whole set sized to its content and centred as one group — so the favourite reads as
                // a peer next to the primary action instead of being flung to the far screen edge.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // Wider gap so the focus scale-up of either button never overlaps its neighbour.
                    horizontalArrangement = spacedBy(28.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PrimaryActions(
                        packageName = app.metadata.packageName.name,
                        isInstalled = isInstalled,
                        updateAvailable = updateAvailable,
                        installState = installState,
                        downloadStatus = downloadStatus,
                        installConfirmed = installConfirmed,
                        onInstallOrUpdate = viewModel::installOrUpdate,
                        onLaunch = viewModel::launch,
                        onUninstall = viewModel::uninstall,
                        onCancel = viewModel::cancel,
                        primaryActionFocusRequester = primaryFocus,
                        // Sized to its content (not stretched full-width) so the favourite sits right next
                        // to it; the group is then centred by the row's arrangement above.
                        modifier = Modifier.width(IntrinsicSize.Min),
                    )
                    TvFavouriteButton(isFavourite = isFavourite, onToggle = viewModel::toggleFavourite)
                }

                if (screenshots.isNotEmpty()) {
                    TvSectionTitle(stringResource(R.string.screenshot))
                    ScreenshotsRow(screenshots)
                }

                if (descriptionHtml.isNotBlank()) {
                    TvSectionTitle(stringResource(R.string.description))
                    TvReadmePreview(
                        html = descriptionHtml,
                        baseUrl = "",
                        javaScriptEnabled = false,
                        viewportPx = viewportPx,
                        onOpenFull = { showDescription = true },
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
            }
            }

            if (showDescription) {
                WebViewDialog(
                    title = stringResource(R.string.description),
                    html = descriptionHtml,
                    unavailable = false,
                    unavailableMessage = "",
                    baseUrl = "",
                    javaScriptEnabled = false,
                    webUrl = app.links?.webSite ?: app.links?.sourceCode ?: "",
                    onDismiss = { showDescription = false },
                )
            }
        }
    }
}

@Composable
internal fun TvBackButton(onBackClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = spacedBy(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .tvFocusFill(RoundedCornerShape(50))
            .tvBringIntoViewOnFocus()
            .clickable(onClick = onBackClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
        Text(stringResource(R.string.tv_back), style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * A TV screen header: a back affordance above a large title tinted with the theme accent. Used in place
 * of the phone's solid accent app-bar on the TV settings / repo-detail / repo-edit screens so they match
 * the rest of the TV UI while still nodding to the accent colour. Pass a [Modifier] carrying
 * `tvDpadDownTo(...)` so "down" drops from the header into the list below.
 */
@Composable
internal fun TvAccentHeader(title: String, onBackClick: () -> Unit, modifier: Modifier = Modifier) {
    // Transparent (no solid fill) so the screen's accent background flows continuously behind it — a
    // solid header band left a hard seam where it met the wash.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = TvOverscan + 8.dp, end = TvOverscan, top = TvOverscan, bottom = 8.dp),
    ) {
        TvBackButton(onBackClick)
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TvMetaChips(category: String?, version: String?, license: String?) {
    FlowRow(horizontalArrangement = spacedBy(8.dp), verticalArrangement = spacedBy(8.dp)) {
        version?.let { TvChip("v$it") }
        category?.let { TvChip(it) }
        license?.let { TvChip(it) }
    }
}

@Composable
internal fun TvChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** The favourite toggle button (heart + label), sat at the right end of the action row next to
 *  Uninstall. Accent heart when on, muted when off. Shared by both TV detail screens. */
@Composable
internal fun TvFavouriteButton(isFavourite: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    // A solid tonal button (not a hairline outline) so it reads as a real peer next to the filled primary
    // action instead of a lonely ghost button. The heart fills with the accent when the app is a
    // favourite.
    FilledTonalButton(
        onClick = onToggle,
        // Scales on focus like the primary action, so it's obvious when the remote is on it.
        modifier = modifier.height(60.dp).widthIn(min = 200.dp).tvFocusScale(1.10f).tvBringIntoViewOnFocus(),
    ) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = null,
            tint = if (isFavourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.favourites))
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
                modifier = Modifier.tvBringIntoViewOnFocus(),
                highlighted = isSuggested,
            ) {
                // Both chips can apply at once (the installed version is also the suggested one).
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            }
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

/**
 * A rendered preview of an app's description / README on a TV detail screen: the real content (in a
 * [ReadmeWebView], so emojis/images/tables/links all show properly), clipped to run from where it starts
 * down to the bottom of the screen ([viewportPx] minus its own top), followed by a "View description"
 * control that opens the full reader. Shared by the catalogue and external screens so both read alike.
 *
 * The WebView is drawn at its real height and clipped by the box, switching to a hardware layer once it's
 * taller than a screen (a software layer would render blank past ~one screenful) — same handling as the
 * reader dialog and the phone build.
 */
@Composable
internal fun TvReadmePreview(
    html: String,
    baseUrl: String,
    javaScriptEnabled: Boolean,
    viewportPx: Int,
    onOpenFull: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var topY by remember(html) { mutableStateOf(0) }
    var contentHeightPx by remember(html) { mutableStateOf(0) }
    val previewHeight = if (viewportPx > 0 && topY in 1 until viewportPx) {
        with(density) { (viewportPx - topY).coerceAtLeast(PREVIEW_MIN_PX).toDp() }
    } else {
        PREVIEW_FALLBACK_HEIGHT
    }
    val scroll = rememberScrollState()
    Column(
        verticalArrangement = spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { topY = it.positionInParent().y.toInt() }
                .height(previewHeight)
                .clipToBounds(),
        ) {
            ReadmeWebView(
                html = html,
                baseUrl = baseUrl,
                javaScriptEnabled = javaScriptEnabled,
                onContentHeight = { contentHeightPx = it },
                scrollState = scroll,
                forceSoftwareLayer = viewportPx <= 0 || contentHeightPx <= viewportPx,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (contentHeightPx > 0) with(density) { contentHeightPx.toDp() } else 600.dp),
            )
        }
        TvOpenDescriptionButton(onClick = onOpenFull)
    }
}

private val PREVIEW_FALLBACK_HEIGHT = 260.dp
private const val PREVIEW_MIN_PX = 160

/**
 * The "View description" control on a TV detail screen: opens the app's description / README in a
 * centred reader ([WebViewDialog]) rendered exactly like the mobile build's, so emojis, images, tables
 * and links all show properly and the couch UI stays sober. Shared by the catalogue and external detail
 * screens so both kinds of app open their description the same way.
 */
@Composable
internal fun TvOpenDescriptionButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp).widthIn(min = 240.dp).tvBringIntoViewOnFocus(),
    ) {
        Text(stringResource(R.string.tv_view_description))
    }
}

@Composable
internal fun TvCentered(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) { content() }
}

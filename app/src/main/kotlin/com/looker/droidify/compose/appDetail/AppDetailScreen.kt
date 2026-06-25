package com.looker.droidify.compose.appDetail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.looker.droidify.R
import com.looker.droidify.compose.appDetail.components.CustomButtonsRow
import com.looker.droidify.compose.appDetail.components.PackageItem
import com.looker.droidify.compose.components.BackButton
import com.looker.droidify.compose.components.DownloadProgressRow
import com.looker.droidify.compose.components.InstallingRow
import com.looker.droidify.data.model.App
import com.looker.droidify.data.model.FilePath
import com.looker.droidify.data.model.Package
import com.looker.droidify.data.model.Permission
import com.looker.droidify.data.model.Repo
import com.looker.droidify.data.model.selectForDevice
import com.looker.droidify.datastore.model.CustomButton
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.utility.text.toAnnotatedString

/**
 * Maximum number of version rows rendered on the detail screen. The screen is a single
 * (non-lazy) scroll column, so every row composes eagerly; a handful of apps ship hundreds
 * of historical releases, which froze the UI. The list is sorted newest-first, so the cap
 * keeps the releases users actually care about.
 */
private const val MAX_VERSIONS_SHOWN = 50

@OptIn(ExperimentalMaterial3Api::class)
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
    val uriHandler = LocalUriHandler.current
    val signatureConflict by viewModel.signatureConflict.collectAsStateWithLifecycle()

    if (signatureConflict) {
        val conflictAppName = (state as? AppDetailState.Success)?.app?.metadata?.name
            ?: viewModel.packageName
        AlertDialog(
            onDismissRequest = viewModel::dismissSignatureConflict,
            title = { Text(stringResource(R.string.signature_conflict_title)) },
            text = {
                Text(stringResource(R.string.install_failed_signature_mismatch, conflictAppName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.uninstall()
                        viewModel.dismissSignatureConflict()
                    },
                ) { Text(stringResource(R.string.uninstall)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissSignatureConflict) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when (state) {
                        AppDetailState.Loading -> Text(stringResource(R.string.application))
                        is AppDetailState.Error -> {}
                        is AppDetailState.Success -> {
                            val app = (state as AppDetailState.Success).app
                            Text(text = app.metadata.name)
                        }
                    }
                },
                navigationIcon = { BackButton(onBackClick) },
            )
        },
    ) { padding ->
        when (state) {
            AppDetailState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
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
                    onInstallOrUpdate = viewModel::installOrUpdate,
                    onLaunch = viewModel::launch,
                    onUninstall = viewModel::uninstall,
                    onCancel = viewModel::cancel,
                    onCustomButtonClick = { url ->
                        try {
                            uriHandler.openUri(url)
                        } catch (_: Exception) {
                        }
                    },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
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
    modifier: Modifier = Modifier,
) {
    val installing = installState == InstallState.Pending || installState == InstallState.Installing
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                !isInstalled -> Button(
                    onClick = onInstallOrUpdate,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.install)) }

                updateAvailable -> Button(
                    onClick = onInstallOrUpdate,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.update)) }

                else -> Button(
                    onClick = onLaunch,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.launch)) }
            }
            if (isInstalled) {
                OutlinedButton(onClick = onUninstall) {
                    Text(stringResource(R.string.uninstall))
                }
            }
        }
    }
}

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
    onInstallOrUpdate: () -> Unit,
    onLaunch: () -> Unit,
    onUninstall: () -> Unit,
    onCancel: () -> Unit,
    onCustomButtonClick: (url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val installedPackage = app.packages?.firstOrNull { it.installed }
    // The newest release this device can actually install (device-aware; see [selectForDevice]).
    // Comparing against the raw suggested code would keep flagging an update on, say, an x86 device,
    // because VLC's suggested code belongs to its (un-installable) arm64 build.
    val installablePackage = remember(packages, app.metadata.suggestedVersionCode) {
        packages.selectForDevice(app.metadata.suggestedVersionCode)?.first
    }
    val updateAvailable = installedPackage != null && installablePackage != null &&
        installedPackage.manifest.versionCode < installablePackage.manifest.versionCode
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .then(modifier),
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
            Text(
                text = app.metadata.summary,
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
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        val suggestedPackage = installablePackage ?: packages.firstOrNull()?.first

        suggestedPackage?.whatsNew?.takeIf { it.isNotBlank() }?.let { whatsNew ->
            Spacer(modifier = Modifier.height(16.dp))
            WhatsNewSection(whatsNew = whatsNew)
        }

        if (app.hasLinks()) {
            Spacer(modifier = Modifier.height(8.dp))
            LinksSection(app = app)
        }

        val permissions = suggestedPackage?.manifest?.permissions.orEmpty()
        if (permissions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            PermissionsSection(permissions = permissions)
        }

        Spacer(modifier = Modifier.height(16.dp))
        val suggestedVersion = installablePackage?.manifest?.versionCode
        // This screen is a single (non-lazy) scroll column, so every version row composes eagerly.
        // Apps with many versions were composing hundreds of rows at once, freezing the UI; cap the
        // list to the newest releases (packages are already sorted newest-first).
        packages.take(MAX_VERSIONS_SHOWN).forEach { (pkg, repo) ->
            val isSuggested = suggestedVersion != null && pkg.manifest.versionCode == suggestedVersion
            PackageItem(
                item = pkg,
                repo = repo,
                onClick = {},
                onLongClick = {},
                backgroundColor = if (isSuggested) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ) {
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
                } else if (pkg.installed) {
                    Text(
                        text = stringResource(R.string.suggested).uppercase(),
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
        val iconUrl = app?.metadata?.icon?.path
        if (!iconUrl.isNullOrBlank()) {
            AsyncImage(
                model = iconUrl,
                contentDescription = null,
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
                    text = "Version: $version",
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

        FilledTonalIconToggleButton(
            checked = isFavorite,
            onCheckedChange = { onToggleFavorite() },
            modifier = Modifier.size(
                IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Narrow),
            ),
        ) {
            val icon = if (isFavorite) {
                R.drawable.ic_favourite_checked
            } else {
                R.drawable.ic_favourite
            }
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
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
            modifier = Modifier.padding(horizontal = 16.dp),
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
        SectionTitle(stringResource(R.string.links))
        links?.webSite?.nonBlank()?.let { url ->
            LinkRow(stringResource(R.string.website), url) { open(url) }
        }
        links?.sourceCode?.nonBlank()?.let { url ->
            LinkRow(stringResource(R.string.source_code), url) { open(url) }
        }
        links?.issueTracker?.nonBlank()?.let { url ->
            LinkRow(stringResource(R.string.issue_tracker), url) { open(url) }
        }
        links?.changelog?.nonBlank()?.let { url ->
            LinkRow(stringResource(R.string.changelog), url) { open(url) }
        }
        links?.translation?.nonBlank()?.let { url ->
            LinkRow(stringResource(R.string.translation), url) { open(url) }
        }
        author?.web?.nonBlank()?.let { url ->
            LinkRow(author?.name?.nonBlank() ?: stringResource(R.string.author_website), url) { open(url) }
        }
        donateUrl?.nonBlank()?.let { url ->
            LinkRow(stringResource(R.string.donate), url) { open(url) }
        }
    }
}

@Composable
private fun LinkRow(
    title: String,
    url: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
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

@Composable
private fun PermissionsSection(permissions: List<Permission>) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
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
                            contentDescription = "screenshot",
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
                    contentDescription = "Close",
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
            )
        }
    }
}

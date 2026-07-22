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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.core.text.HtmlCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.appDetail.AppDetailState
import com.looker.droidify.compose.appDetail.AppDetailViewModel
import com.looker.droidify.compose.appDetail.PrimaryActions
import com.looker.droidify.compose.appDetail.ScreenshotsRow
import com.looker.droidify.compose.appList.AppMinimalIcon
import com.looker.droidify.compose.components.TvOverscan
import com.looker.droidify.compose.components.tvBringIntoViewOnFocus
import com.looker.droidify.compose.components.tvFocusFill
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
                    if (runCatching { primaryFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                    delay(50)
                }
            }

            val screenshots = remember(app.screenshots) {
                (app.screenshots?.tv?.takeIf { it.isNotEmpty() } ?: app.screenshots?.phone).orEmpty()
            }
            val description = remember(app.metadata.description) {
                HtmlCompat.fromHtml(app.metadata.description.raw, HtmlCompat.FROM_HTML_MODE_COMPACT)
                    .toString().trim()
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    // Any key press marks startup focus as settled, so it's never re-stolen (see above).
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) userInteracted = true
                        false
                    }
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = TvOverscan + 16.dp, vertical = TvOverscan),
                verticalArrangement = spacedBy(24.dp),
            ) {
                TvBackButton(onBackClick)

                // Header: icon + name + author + meta chips
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

                // The one big action button (holds startup focus) + favourite
                Column(verticalArrangement = spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
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
                    )
                    TvFavouriteButton(isFavourite = isFavourite, onToggle = viewModel::toggleFavourite)
                }

                if (screenshots.isNotEmpty()) {
                    TvSectionTitle(stringResource(R.string.screenshot))
                    ScreenshotsRow(screenshots)
                }

                if (description.isNotBlank()) {
                    TvSectionTitle(stringResource(R.string.description))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.tvBringIntoViewOnFocus(),
                    )
                }

                if (packages.isNotEmpty()) {
                    TvSectionTitle(stringResource(R.string.versions))
                    TvVersionsList(packages)
                }
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

@Composable
internal fun TvFavouriteButton(isFavourite: Boolean, onToggle: () -> Unit) {
    OutlinedButton(
        onClick = onToggle,
        modifier = Modifier.height(52.dp).width(260.dp).tvBringIntoViewOnFocus(),
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

@Composable
private fun TvVersionsList(packages: List<Pair<com.looker.droidify.data.model.Package, com.looker.droidify.data.model.Repo>>) {
    Column(verticalArrangement = spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        // Cap to the newest handful — the whole history would make this an endless page on a remote.
        packages.take(8).forEach { (pkg, repo) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .tvFocusFill(RoundedCornerShape(16.dp))
                    .tvBringIntoViewOnFocus()
                    .clickable {}
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    text = "v${pkg.manifest.versionName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (pkg.installed) {
                    Text(
                        text = stringResource(R.string.installed),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = repo.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) { content() }
}

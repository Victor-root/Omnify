package com.looker.droidify.compose.appDetail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.hilt.lifecycle.viewmodel.HiltViewModelFactory
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.looker.droidify.R
import com.looker.droidify.compose.components.tvFocusScale
import com.looker.droidify.compose.externalApps.ExternalAppsViewModel
import com.looker.droidify.compose.theme.EmphasizedDecelerate
import com.looker.droidify.compose.theme.SOURCE_SWITCH_MS
import com.looker.droidify.data.FollowedSource
import com.looker.droidify.data.catalogueBuildIsOlder
import com.looker.droidify.data.model.Package
import com.looker.droidify.data.model.Repo
import com.looker.droidify.data.model.selectForDevice
import com.looker.droidify.data.signerMismatch
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.releaseVersionLabel
import com.looker.droidify.network.DataSize
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * One tab of an app's version list: a repository offering the app, or a tracked external source of the
 * same package ([external]). [version] is the newest one it offers this device.
 */
internal data class SourceTab(
    val source: FollowedSource,
    val name: String,
    val version: String?,
    val external: ExternalApp? = null,
)

/**
 * The build an update of the app takes among [this], every repository's: the followed repository's own
 * when [followed] is one, which stays true even when another repository has a newer one.
 */
internal fun List<Pair<Package, Repo>>.updateBuild(followed: FollowedSource?, suggested: Long): Pair<Package, Repo>? {
    val repoId = (followed as? FollowedSource.Repository)?.repoId
    return (if (repoId == null) this else filter { it.second.id == repoId }).selectForDevice(suggested)
}

/**
 * The tabs of an app's version list: each repository offering it ([packages], newest build this device
 * runs as its version), then each tracked external source of the same package ([external]).
 */
internal fun sourceTabs(packages: List<Pair<Package, Repo>>, external: List<ExternalApp>): List<SourceTab> =
    packages.map { it.second }.distinctBy { it.id }.map { repo ->
        SourceTab(
            source = FollowedSource.Repository(repo.id),
            name = repo.name,
            version = packages.filter { it.second.id == repo.id }
                .selectForDevice(Long.MAX_VALUE)
                ?.first?.manifest?.versionName,
        )
    } + external.map { app ->
        SourceTab(
            source = FollowedSource.External(app.key),
            name = app.sourceLabel,
            version = releaseVersionLabel(app.latestApkName, app.latestTag).ifEmpty { null },
            external = app,
        )
    }

/**
 * The source the updates of the copy on the device come from, whose tab gets the star: the one followed,
 * else the one Omnify picks, a tracked source that takes them ([updater]) or the repository with the
 * build it would install ([installableRepo]). Null while the app isn't installed.
 */
internal fun updatingSourceOf(
    installed: Boolean,
    updater: ExternalApp?,
    followed: FollowedSource?,
    installableRepo: Repo?,
): FollowedSource? = when {
    !installed -> null
    updater != null -> FollowedSource.External(updater.key)
    followed is FollowedSource.Repository -> followed
    else -> installableRepo?.let { FollowedSource.Repository(it.id) }
}

/** [source]'s name, from the repositories offering the app ([packages]) or its tracked sources. */
internal fun sourceNameOf(
    source: FollowedSource?,
    packages: List<Pair<Package, Repo>>,
    external: List<ExternalApp>,
): String? = when (source) {
    is FollowedSource.External -> external.firstOrNull { it.key == source.key }?.sourceLabel
    is FollowedSource.Repository -> packages.firstOrNull { it.second.id == source.repoId }?.second?.name
    null -> null
}

/**
 * The line saying where an installed app's updates come from, when more than one source offers it
 * ([sourceCount]): [updatingName], picked by the user or by Omnify, or that the followed source is gone.
 */
@Composable
internal fun updateSourceNote(
    installed: Boolean,
    choice: UpdateSourceChoice,
    updatingName: String?,
    sourceCount: Int,
): String? = when {
    !installed -> null
    choice.fellBack -> stringResource(R.string.followed_source_gone)
    sourceCount < 2 || updatingName == null -> null
    choice.followed != null -> stringResource(R.string.updates_from_FORMAT, updatingName)
    else -> stringResource(R.string.updates_from_auto_FORMAT, updatingName)
}

/**
 * Following another source: [request] waiting for the user to confirm, then, for one signing with
 * another key, [pendingSwitch], the source to install from once the copy on the device is gone.
 */
@Stable
internal class SourceFollowState {
    var request by mutableStateOf<FollowRequest?>(null)
    var pendingSwitch by mutableStateOf<SourceTab?>(null)

    /** Asks to follow a tab, for an app whose copy on the device is [installed]; null while it isn't. */
    fun followTab(
        installed: InstalledInfo?,
        packages: List<Pair<Package, Repo>>,
        currentSource: String?,
    ): ((SourceTab) -> Unit)? = installed?.let { info ->
        { tab -> request = followRequestOf(tab, info, packages, currentSource ?: info.source) }
    }
}

@Composable
internal fun rememberSourceFollowState(): SourceFollowState = remember { SourceFollowState() }

/**
 * [state]'s confirmation dialog, and what confirming it does: following the source, installing its newer
 * build over the copy on the device, or, for a source signing with another key, uninstalling that copy
 * and, once it is gone, following the source and installing from it. An uninstall the user turns down
 * leaves everything as it was.
 */
@Composable
internal fun SourceFollowDialogs(
    state: SourceFollowState,
    appName: String,
    installedInfo: InstalledInfo?,
    packages: List<Pair<Package, Repo>>,
    externalViewModel: ExternalAppsViewModel?,
    onFollow: (FollowedSource) -> Unit,
    onInstallVersion: (Package, Repo) -> Unit,
    onUninstall: () -> Unit,
) {
    val installFrom: (SourceTab) -> Unit = { tab ->
        when (val source = tab.source) {
            is FollowedSource.Repository -> packages.filter { it.second.id == source.repoId }
                .selectForDevice(Long.MAX_VALUE)
                ?.let { (pkg, repo) -> onInstallVersion(pkg, repo) }
            is FollowedSource.External -> tab.external?.let { externalViewModel?.installOrUpdate(it) }
        }
    }
    // installedInfo is re-read on every install/uninstall; null means the app is now gone.
    LaunchedEffect(installedInfo, state.pendingSwitch) {
        val pending = state.pendingSwitch ?: return@LaunchedEffect
        if (installedInfo == null) {
            onFollow(pending.source)
            installFrom(pending)
            state.pendingSwitch = null
        }
    }
    state.request?.let { request ->
        FollowSourceDialog(
            request = request,
            appName = appName,
            onFollow = {
                onFollow(request.tab.source)
                if (request.installsNow) installFrom(request.tab)
                state.request = null
            },
            onReplace = {
                state.pendingSwitch = request.tab
                onUninstall()
                state.request = null
            },
            onDismiss = { state.request = null },
        )
    }
}

/**
 * The sources of an app's version list side by side in one rounded track, each its name over the
 * version it offers, the one on screen raised on a white piece that glides to the source picked, and a
 * star on the one the updates come from.
 *
 * They share the track's width while their names fit in it. When they don't, each takes the width of its
 * whole name and the track scrolls sideways, keeping the source on screen in view: a cut name read as
 * "IzzyOnDroi…" and "Breezy Wea…", which is no way to tell sources apart.
 */
@Composable
internal fun SourceSegments(
    tabs: List<SourceTab>,
    selected: FollowedSource?,
    updating: FollowedSource?,
    onSelect: (SourceTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val selectedIndex = tabs.indexOfFirst { it.source == selected }.coerceAtLeast(0)
    var trackWidthPx by remember { mutableIntStateOf(0) }
    // Where each source sits in the track, by index: its left edge and width, once laid out.
    val bounds = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }
    val target = bounds[selectedIndex]
    // The piece is put in place as the page opens, and glides only when another source is picked.
    val pieceX = remember { Animatable(0f) }
    val pieceWidth = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(target, trackWidthPx) {
        val (x, width) = target ?: return@LaunchedEffect
        val scrollTarget = x - (trackWidthPx - width) / 2
        if (placed) {
            val spec = tween<Float>(SOURCE_SWITCH_MS, easing = EmphasizedDecelerate)
            launch { pieceX.animateTo(x.toFloat(), spec) }
            launch { pieceWidth.animateTo(width.toFloat(), spec) }
            scroll.animateScrollTo(scrollTarget)
        } else {
            pieceX.snapTo(x.toFloat())
            pieceWidth.snapTo(width.toFloat())
            scroll.scrollTo(scrollTarget)
            placed = true
        }
    }
    val segmentMinWidth = with(density) { (if (tabs.isEmpty()) 0 else trackWidthPx / tabs.size).toDp() }
    val followedLabel = stringResource(R.string.source_followed)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(SegmentTrackShape)
            .background(colors.primary.copy(alpha = 0.13f).compositeOver(colors.surfaceContainerHigh))
            .padding(4.dp)
            .onSizeChanged { trackWidthPx = it.width },
    ) {
        Box(modifier = Modifier.clip(SegmentShape).horizontalScroll(scroll)) {
            if (placed) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .wrapContentWidth(Alignment.Start)
                        .width(with(density) { pieceWidth.value.toDp() })
                        .offset { IntOffset(pieceX.value.roundToInt(), 0) }
                        .shadow(1.dp, SegmentShape)
                        // Brighter than the track in both themes: white in a light one, raised in a dark one.
                        .background(colors.surfaceBright, SegmentShape),
                )
            }
            Row(modifier = Modifier.selectableGroup()) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = tab.source == selected
                    val isUpdating = tab.source == updating
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .widthIn(min = segmentMinWidth)
                            .heightIn(min = 52.dp)
                            .onPlaced { bounds[index] = it.positionInParent().x.roundToInt() to it.size.width }
                            .clip(SegmentShape)
                            .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) })
                            .then(
                                if (isUpdating) {
                                    Modifier.semantics { contentDescription = "${tab.name}, $followedLabel" }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (isUpdating) {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = if (isSelected) colors.primary else colors.onSurfaceVariant,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                            Text(
                                text = tab.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isSelected) colors.primary else colors.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        tab.version?.let { version ->
                            Text(
                                text = version,
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val SegmentTrackShape = RoundedCornerShape(18.dp)
private val SegmentShape = RoundedCornerShape(14.dp)

/**
 * Under [SourceSegments], the same height on every source so nothing moves when another is picked: on
 * the one the updates come from ([updating]), saying so, and that Omnify picked it when it did
 * ([pinned] false); on any other, where they come from instead ([updatingName]) and the button that
 * follows this one ([onFollow]).
 */
@Composable
internal fun SourceStatusRow(
    updating: Boolean,
    pinned: Boolean,
    updatingName: String?,
    onFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 6.dp),
    ) {
        if (updating) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(16.dp),
            )
            val followed = stringResource(R.string.source_followed)
            val automatic = stringResource(R.string.source_followed_automatic)
            Text(
                text = buildAnnotatedString {
                    append(followed)
                    if (!pinned) withStyle(SpanStyle(color = colors.outline)) { append(" · $automatic") }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.StarOutline,
                contentDescription = null,
                tint = colors.outline,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = updatingName?.let { stringResource(R.string.updates_come_from_FORMAT, it) }
                    ?: stringResource(R.string.source_follow),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onFollow) {
                Text(stringResource(R.string.follow_source))
            }
        }
    }
}

/**
 * Under the tabs: on the source the app's updates come from, a chip saying so ([pinned] false when
 * Omnify picked it rather than the user); on any other, the button that follows it instead.
 */
@Composable
internal fun FollowSourceRow(
    updating: Boolean,
    pinned: Boolean,
    onFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (updating) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier
                .heightIn(min = 36.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                .padding(start = 12.dp, end = 16.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(if (pinned) R.string.source_followed else R.string.source_followed_auto),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    } else {
        OutlinedButton(
            onClick = onFollow,
            contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
            modifier = modifier.tvFocusScale(),
        ) {
            Icon(imageVector = Icons.Outlined.StarOutline, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text = stringResource(R.string.source_follow), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Whether a source signs the app with the key of the copy on the device. */
internal enum class SigningMatch { SAME, OTHER, UNKNOWN }

/** [signers] against [installedSigner], by the one shared rule ([signerMismatch]). */
internal fun signingMatch(installedSigner: String?, signers: Collection<String>): SigningMatch = when {
    installedSigner.isNullOrBlank() || signers.isEmpty() -> SigningMatch.UNKNOWN
    signerMismatch(installedSigner, signers) -> SigningMatch.OTHER
    else -> SigningMatch.SAME
}

/**
 * Following [tab] for the app's updates, waiting for the user to confirm. [version] and [size] are what
 * it would install, [installsNow] whether that is newer than the copy on the device, [currentSource] the
 * source the updates come from today, [systemApp] whether that copy is part of the system.
 */
internal data class FollowRequest(
    val tab: SourceTab,
    val version: String?,
    val size: String?,
    val signing: SigningMatch,
    val installsNow: Boolean,
    val currentSource: String,
    val systemApp: Boolean,
)

/**
 * Following [tab] for an app whose copy on the device is [installed], among every repository's builds
 * of it ([packages]), with the updates coming from [currentSource] today: what it would install, and
 * whether that can go over the copy on the device.
 */
internal fun followRequestOf(
    tab: SourceTab,
    installed: InstalledInfo,
    packages: List<Pair<Package, Repo>>,
    currentSource: String,
): FollowRequest = when (val source = tab.source) {
    is FollowedSource.Repository -> {
        val target = packages.filter { it.second.id == source.repoId }.selectForDevice(Long.MAX_VALUE)?.first
        FollowRequest(
            tab = tab,
            version = target?.manifest?.versionName,
            size = target?.apk?.size?.toString(),
            signing = signingMatch(installed.signer, target?.manifest?.signer.orEmpty()),
            installsNow = target != null &&
                !catalogueBuildIsOlder(installed.version, target.manifest.versionName) &&
                target.manifest.versionCode > installed.versionCode,
            currentSource = currentSource,
            systemApp = installed.isSystemApp,
        )
    }
    is FollowedSource.External -> FollowRequest(
        tab = tab,
        version = tab.version,
        size = tab.external?.latestApkSize?.let { DataSize(it).toString() },
        signing = signingMatch(installed.signer, tab.external?.latestApkSigners.orEmpty()),
        installsNow = tab.external?.hasUpdateGiven(installed.version) == true,
        currentSource = currentSource,
        systemApp = installed.isSystemApp,
    )
}

/**
 * Confirms following [request]'s source. Same key: updates simply come from there from now on, and its
 * newer build installs right away over the copy on the device. Another key: Android refuses that, so
 * following it means uninstalling first ([onReplace]), which loses the app's data, said plainly along
 * with how to keep it; a system app can't be uninstalled at all, which it says instead.
 */
@Composable
internal fun FollowSourceDialog(
    request: FollowRequest,
    appName: String,
    onFollow: () -> Unit,
    onReplace: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = request.tab.name
    if (request.signing == SigningMatch.OTHER) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_tabler_alert_triangle),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = {
                Text(stringResource(R.string.follow_source_other_key_FORMAT, name), textAlign = TextAlign.Center)
            },
            text = {
                if (request.systemApp) {
                    Text(stringResource(R.string.follow_source_system_DESC, appName))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            stringResource(R.string.follow_source_other_key_DESC, request.currentSource, name, appName),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.follow_source_export_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (request.systemApp) {
                    TextButton(onClick = onDismiss, modifier = Modifier.tvFocusScale()) {
                        Text(stringResource(R.string.ok))
                    }
                } else {
                    TextButton(
                        onClick = onReplace,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.tvFocusScale(),
                    ) {
                        Text(stringResource(R.string.follow_source_reinstall_FORMAT, name))
                    }
                }
            },
            dismissButton = if (request.systemApp) {
                null
            } else {
                {
                    TextButton(onClick = onDismiss, modifier = Modifier.tvFocusScale()) {
                        Text(stringResource(R.string.follow_source_keep_FORMAT, request.currentSource))
                    }
                }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.follow_source_FORMAT, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(text = name, style = MaterialTheme.typography.titleSmall)
                    request.version?.let { version ->
                        Text(
                            text = if (request.size != null) {
                                stringResource(R.string.install_variant_file_FORMAT, version, request.size)
                            } else {
                                stringResource(R.string.version_FORMAT, version)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (request.signing == SigningMatch.SAME) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.follow_source_same_key),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    if (request.installsNow && request.version != null) {
                        stringResource(R.string.follow_source_installs_FORMAT, request.version, name)
                    } else {
                        stringResource(R.string.follow_source_next_FORMAT, name)
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onFollow, modifier = Modifier.tvFocusScale()) {
                Text(
                    stringResource(
                        if (request.installsNow) R.string.follow_source_and_update else R.string.follow_source,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.tvFocusScale()) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * The catalogue page's ViewModel for [packageName], for a page that is not that app's catalogue page: a
 * tracked source's own page shows the catalogue's side of the same app as tabs of its version list.
 * Given the argument its own page would give it, and kept under a key of its own in the current
 * destination, next to that page's own ViewModel.
 */
@Composable
internal fun catalogueViewModel(packageName: String): AppDetailViewModel {
    val owner = LocalViewModelStoreOwner.current
    // A navigation destination, which is both.
    check(owner is HasDefaultViewModelProviderFactory) { "No destination to keep it in" }
    return viewModel(
        viewModelStoreOwner = owner,
        key = "catalogue:$packageName",
        factory = HiltViewModelFactory(LocalContext.current, owner.defaultViewModelProviderFactory),
        extras = MutableCreationExtras(owner.defaultViewModelCreationExtras).apply {
            set(DEFAULT_ARGS_KEY, bundleOf(CATALOGUE_PACKAGE_ARG to packageName))
        },
    )
}

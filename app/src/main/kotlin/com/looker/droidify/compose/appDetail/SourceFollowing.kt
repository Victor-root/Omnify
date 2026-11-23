package com.looker.droidify.compose.appDetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.components.tvFocusScale
import com.looker.droidify.compose.theme.LocalOnAccentBarColor
import com.looker.droidify.data.FollowedSource
import com.looker.droidify.data.catalogueBuildIsOlder
import com.looker.droidify.data.model.Package
import com.looker.droidify.data.model.Repo
import com.looker.droidify.data.model.selectForDevice
import com.looker.droidify.data.signerMismatch
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.network.DataSize

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
 * The tab's name over the version it offers, with a star on the source the app's updates come from.
 * Its own tab, since a tab's text slot is sized for a single line.
 */
@Composable
internal fun SourceTabItem(tab: SourceTab, selected: Boolean, updating: Boolean, onClick: () -> Unit) {
    val followedLabel = stringResource(R.string.source_followed)
    Tab(
        selected = selected,
        onClick = onClick,
        // TV only: the focused tab scales up. No-op on touch.
        modifier = Modifier
            .tvFocusScale()
            .heightIn(min = SOURCE_TAB_HEIGHT)
            .then(
                if (updating) Modifier.semantics { contentDescription = "${tab.name}, $followedLabel" } else Modifier,
            ),
        selectedContentColor = LocalOnAccentBarColor.current,
        unselectedContentColor = LocalOnAccentBarColor.current.copy(alpha = 0.7f),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (updating) {
                    Icon(imageVector = Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(14.dp))
                }
                Text(
                    text = tab.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            tab.version?.let { version ->
                Text(
                    text = version,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Room for a source tab's two lines and the pill under them. */
private val SOURCE_TAB_HEIGHT = 64.dp

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

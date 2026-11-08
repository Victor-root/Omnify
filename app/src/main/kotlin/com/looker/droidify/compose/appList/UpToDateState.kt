package com.looker.droidify.compose.appList

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.externalApps.ExternalAppIcon
import com.looker.droidify.data.model.AppMinimal
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.utility.common.extension.getPackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** An installed app Omnify keeps up to date: from the catalogue, or from a source the user follows. */
@Immutable
sealed interface FollowedApp {
    val packageName: String

    data class Catalogue(val app: AppMinimal) : FollowedApp {
        override val packageName: String get() = app.packageName.name
    }

    data class External(val app: ExternalApp, override val packageName: String) : FollowedApp
}

/** A [FollowedApp] with the version it was last updated to, and when. */
@Immutable
private data class RecentUpdate(val app: FollowedApp, val version: String?, val updatedAt: Long)

/**
 * The Updates tab once there is nothing to update: says so, how many apps that covers, when updates
 * were last checked for and how often they are, and which apps were updated lately, so an empty tab
 * reads as everything being looked after rather than as nothing happening.
 */
@Composable
internal fun UpToDateState(
    followedApps: List<FollowedApp>,
    lastCheck: Long?,
    checkInterval: Duration?,
    isChecking: Boolean,
    onCheckNow: () -> Unit,
    onOpenApp: (FollowedApp) -> Unit,
) {
    val context = LocalContext.current
    // Ticks once a minute, so "12 min ago" doesn't stand still while the tab is open.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1.minutes)
            value = System.currentTimeMillis()
        }
    }
    val recentUpdates by produceState(emptyList(), followedApps) {
        value = withContext(Dispatchers.IO) { context.recentUpdates(followedApps) }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 36.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.widthIn(max = 480.dp),
        ) {
            CheckBadge()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.everything_up_to_date),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                if (followedApps.isNotEmpty()) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.up_to_date_apps,
                            followedApps.size,
                            followedApps.size,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                InfoTile(
                    icon = R.drawable.ic_time,
                    label = stringResource(R.string.up_to_date_checked),
                    value = lastCheck?.let { relativeTime(it, now) }
                        ?: stringResource(R.string.up_to_date_not_checked_yet),
                    modifier = Modifier.weight(1f),
                )
                InfoTile(
                    icon = R.drawable.ic_sync,
                    label = stringResource(R.string.up_to_date_auto_check),
                    value = checkInterval?.let { checkFrequency(it) } ?: stringResource(R.string.up_to_date_auto_off),
                    modifier = Modifier.weight(1f),
                )
            }
            if (recentUpdates.isNotEmpty()) {
                RecentUpdates(updates = recentUpdates, now = now, onOpenApp = onOpenApp)
            }
            FilledTonalButton(onClick = onCheckNow, enabled = !isChecking) {
                Icon(
                    painter = painterResource(R.drawable.ic_sync),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.up_to_date_check_now))
            }
        }
    }
}

@Composable
private fun CheckBadge() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(96.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun InfoTile(icon: Int, label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RecentUpdates(updates: List<RecentUpdate>, now: Long, onOpenApp: (FollowedApp) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.up_to_date_recent),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraLarge),
        ) {
            updates.forEachIndexed { index, update ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                RecentUpdateRow(update = update, now = now, onClick = { onOpenApp(update.app) })
            }
        }
    }
}

@Composable
private fun RecentUpdateRow(update: RecentUpdate, now: Long, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        when (val app = update.app) {
            is FollowedApp.Catalogue -> AppMinimalIcon(
                app = app.app,
                isInstalled = true,
                modifier = Modifier.size(40.dp),
            )
            is FollowedApp.External -> ExternalAppIcon(app = app.app, isInstalled = true, size = 40.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = when (val app = update.app) {
                    is FollowedApp.Catalogue -> app.app.name
                    is FollowedApp.External -> app.app.label
                },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            Text(
                // Lower case, as it follows the version mid-sentence ("1.2, 3 days ago").
                text = listOfNotNull(
                    update.version,
                    relativeTime(update.updatedAt, now).replaceFirstChar { it.lowercase() },
                ).joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun checkFrequency(interval: Duration): String =
    if (interval >= 1.days) {
        stringResource(R.string.up_to_date_once_a_day)
    } else {
        val hours = interval.inWholeHours.toInt()
        pluralStringResource(R.plurals.up_to_date_every_hours, hours, hours)
    }

private fun relativeTime(time: Long, now: Long): String =
    DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString()

/** The [apps] updated in the last [RECENT_WINDOW], most recent first, at most [RECENT_COUNT] of them. */
private fun Context.recentUpdates(apps: List<FollowedApp>): List<RecentUpdate> {
    val since = System.currentTimeMillis() - RECENT_WINDOW.inWholeMilliseconds
    return apps.mapNotNull { app ->
        val info = packageManager.getPackageInfoCompat(app.packageName, signatureFlag = 0) ?: return@mapNotNull null
        // The first install is no update.
        if (info.lastUpdateTime <= info.firstInstallTime || info.lastUpdateTime < since) return@mapNotNull null
        RecentUpdate(app = app, version = info.versionName, updatedAt = info.lastUpdateTime)
    }.sortedByDescending { it.updatedAt }.take(RECENT_COUNT)
}

private val RECENT_WINDOW = 30.days
private const val RECENT_COUNT = 3

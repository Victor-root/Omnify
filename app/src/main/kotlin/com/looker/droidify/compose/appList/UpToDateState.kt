package com.looker.droidify.compose.appList

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.components.HeroStatItem
import com.looker.droidify.compose.components.PremiumCard
import com.looker.droidify.compose.components.SectionHeading
import com.looker.droidify.compose.components.premiumCardBorder
import com.looker.droidify.compose.components.tvFocusFill
import com.looker.droidify.compose.components.tvFocusScale
import com.looker.droidify.compose.externalApps.ExternalAppIcon
import com.looker.droidify.data.model.AppMinimal
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.utility.common.extension.getPackageInfoCompat
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
 * Whether the up-to-date state is to make its entrance: updates were listed since it last showed, so
 * the last of them has just left. Opened with nothing to update, it simply stands there.
 */
@Stable
internal class UpToDateEntrance {
    var due by mutableStateOf(false)
}

/** The [UpToDateEntrance] of a list of updates, due once [hasUpdates] has been true. */
@Composable
internal fun rememberUpToDateEntrance(hasUpdates: Boolean): UpToDateEntrance {
    val entrance = remember { UpToDateEntrance() }
    LaunchedEffect(hasUpdates) { if (hasUpdates) entrance.due = true }
    return entrance
}

/** Every installed app Omnify updates, each once: a package in the catalogue and followed from a source
 *  too counts as the catalogue's. */
internal fun followedApps(catalogue: List<AppMinimal>, external: List<ExternalApp>): List<FollowedApp> {
    val catalogueApps = catalogue.map { FollowedApp.Catalogue(it) }
    val cataloguePackages = catalogueApps.mapTo(HashSet()) { it.packageName }
    return catalogueApps + external.mapNotNull { app ->
        app.packageName?.takeIf { it !in cataloguePackages }?.let { FollowedApp.External(app, it) }
    }
}

/**
 * The Updates tab once there is nothing to update: says so, how many apps that covers, when updates
 * were last checked for and how often they are, and which apps were updated lately, so an empty tab
 * reads as everything being looked after rather than as nothing happening.
 *
 * Fits in [maxHeight], the room the tab has, when it is known: the latest updates then show only as
 * many as fit whole, so that nothing below them needs a scroll to be reached.
 *
 * When [entrance] is due, it makes one: the card rises into place, the check pops in just after and the
 * latest updates follow, for the moment the last update has just been installed. Read in layer blocks,
 * so the entrance redraws the card without recomposing it.
 */
@Composable
internal fun UpToDateState(
    entrance: UpToDateEntrance,
    maxHeight: Dp?,
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
    val entering = remember { MutableTransitionState(!entrance.due).apply { targetState = true } }
    LaunchedEffect(Unit) { entrance.due = false }
    val shown = rememberTransition(entering, label = "upToDateEntrance")
    val card = shown.animateFloat(
        transitionSpec = { tween(ENTRANCE_MS, easing = FastOutSlowInEasing) },
        label = "card",
    ) { if (it) 1f else 0f }
    val badge = shown.animateFloat(
        transitionSpec = { tween(ENTRANCE_MS, delayMillis = BADGE_DELAY_MS, easing = EaseOutBack) },
        label = "badge",
    ) { if (it) 1f else 0f }
    val recent = shown.animateFloat(
        transitionSpec = { tween(ENTRANCE_MS, delayMillis = RECENT_DELAY_MS, easing = FastOutSlowInEasing) },
        label = "recent",
    ) { if (it) 1f else 0f }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier)
            .padding(16.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            // Shaped like an app page's hero card: the state, then its figures, then the action.
            PremiumCard(modifier = Modifier.rise(card)) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(20.dp),
                ) {
                    CheckBadge(
                        modifier = Modifier.graphicsLayer {
                            alpha = badge.value.coerceIn(0f, 1f)
                            scaleX = BADGE_START_SCALE + (1f - BADGE_START_SCALE) * badge.value
                            scaleY = scaleX
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.everything_up_to_date),
                        style = MaterialTheme.typography.titleLarge,
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
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        HeroStatItem(
                            label = stringResource(R.string.up_to_date_checked),
                            value = lastCheck?.let { relativeTime(it, now) }
                                ?: stringResource(R.string.up_to_date_not_checked_yet),
                            modifier = Modifier.weight(1f),
                        )
                        VerticalDivider(
                            modifier = Modifier.height(28.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        HeroStatItem(
                            label = stringResource(R.string.up_to_date_auto_check),
                            value = checkInterval?.let { checkFrequency(it) }
                                ?: stringResource(R.string.up_to_date_auto_off),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = onCheckNow, enabled = !isChecking, modifier = Modifier.tvFocusScale()) {
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
            if (recentUpdates.isNotEmpty()) {
                RecentUpdates(
                    updates = recentUpdates,
                    now = now,
                    onOpenApp = onOpenApp,
                    // Without a known height a weight would leave it no room at all.
                    modifier = (if (maxHeight != null) Modifier.weight(1f, fill = false) else Modifier).rise(recent),
                )
            }
        }
    }
}

/** Fades [progress] in while rising the last few dp into place. */
private fun Modifier.rise(progress: State<Float>): Modifier = graphicsLayer {
    alpha = progress.value
    translationY = (1f - progress.value) * RISE.toPx()
}

@Composable
private fun CheckBadge(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(64.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(34.dp),
        )
    }
}

/** The latest updates under their heading, as many as fit whole: none fitting, the heading goes too. */
@Composable
private fun RecentUpdates(
    updates: List<RecentUpdate>,
    now: Long,
    onOpenApp: (FollowedApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    WholeRowsOnly(
        modifier = modifier.fillMaxWidth(),
        card = {
            // The same frame as PremiumCard, drawn behind the rows that fit.
            val shape = MaterialTheme.shapes.large
            Box(
                Modifier
                    .then(premiumCardBorder(shape))
                    .background(MaterialTheme.colorScheme.surface, shape),
            )
        },
        heading = {
            SectionHeading(
                title = stringResource(R.string.up_to_date_recent),
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            )
        },
    ) {
        updates.forEachIndexed { index, update ->
            Column {
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
            // TV only: a soft accent fill behind the focused row (no-op on touch).
            .tvFocusFill(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        when (val app = update.app) {
            is FollowedApp.Catalogue -> AppMinimalIcon(
                app = app.app,
                isInstalled = true,
                modifier = Modifier.size(36.dp),
            )
            is FollowedApp.External -> ExternalAppIcon(app = app.app, isInstalled = true, size = 36.dp)
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

/**
 * [heading], then [rows] top to bottom over a [card] of their size, leaving out the rows that would not
 * fit whole in its height. With none fitting, nothing shows.
 */
@Composable
private fun WholeRowsOnly(
    card: @Composable () -> Unit,
    heading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    rows: @Composable () -> Unit,
) {
    Layout(contents = listOf(card, heading, rows), modifier = modifier) { (cards, headings, rowItems), constraints ->
        val loose = constraints.copy(minHeight = 0)
        val headingPlaceables = headings.map { it.measure(loose) }
        val headingHeight = headingPlaceables.sumOf { it.height }
        var rowsHeight = 0
        val shown = rowItems.map { it.measure(loose) }.takeWhile { row ->
            (headingHeight + rowsHeight + row.height <= constraints.maxHeight)
                .also { fits -> if (fits) rowsHeight += row.height }
        }
        if (shown.isEmpty()) return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        val cardPlaceables = cards.map {
            it.measure(Constraints.fixed(constraints.maxWidth, rowsHeight))
        }
        layout(constraints.maxWidth, headingHeight + rowsHeight) {
            var y = 0
            headingPlaceables.forEach { placeable ->
                placeable.place(0, y)
                y += placeable.height
            }
            cardPlaceables.forEach { it.place(0, y) }
            shown.forEach { placeable ->
                placeable.place(0, y)
                y += placeable.height
            }
        }
    }
}

/** "3 minutes ago", or "Just now" under a minute, where Android would say "0 minutes ago". */
@Composable
private fun relativeTime(time: Long, now: Long): String =
    if (now - time < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.up_to_date_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }

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

private const val ENTRANCE_MS = 420
private const val BADGE_DELAY_MS = 140
private const val RECENT_DELAY_MS = 240
private const val BADGE_START_SCALE = 0.4f
private val RISE = 24.dp

private val RECENT_WINDOW = 30.days
private const val RECENT_COUNT = 3

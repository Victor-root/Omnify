package com.looker.droidify.compose.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.theme.LocalIsTelevision

/**
 * The shared "hero card" shell for an app's detail screen: a centred icon, bold name, an optional
 * subtitle, an optional favourite toggle overlaid top-end, an optional stats row, the primary action
 * content, and an optional footer line — used identically by the F-Droid catalogue and the external
 * (GitHub/GitLab/Codeberg) detail screens, so the two read as the same app page rather than two
 * different designs. Each screen supplies its own icon/actions content; only the card's shape, spacing
 * and stats-row look live here.
 */
@Composable
fun HeroCard(
    icon: @Composable () -> Unit,
    name: String,
    subtitle: String?,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    // TV only: the screen's startup-focus target, attached to the heart. Unlike the primary action
    // button (further down, past the icon/name/stats), the heart sits at the very top of the card —
    // already fully visible the instant the screen opens — so landing focus here can never trigger a
    // scroll-into-view that outruns the still-settling layout and clips the card under the top bar.
    favoriteFocusRequester: FocusRequester? = null,
    // Android's own "App info" page (uninstall, clear cache/data, permissions, battery, notifications).
    // Overlaid top-start, mirroring the favourite heart at top-end, so it no longer competes for space
    // with the primary action buttons below (where a long localised label, e.g. French "Mettre à jour",
    // could otherwise get squeezed and truncated). Only shown once there's somewhere for it to go
    // (an installed app); null hides it entirely.
    onManageClick: (() -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    stats: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                icon()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (badge != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    badge()
                }
                if (stats != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    stats()
                }
                Spacer(modifier = Modifier.height(16.dp))
                actions()
                if (footer != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    footer()
                }
            }

            // The "manage" gear, mirroring the heart on the opposite corner: no container, overlaid
            // top-start so it doesn't disturb the centred column either.
            if (onManageClick != null) {
                IconButton(
                    onClick = onManageClick,
                    // TV: square so the focus halo is a clean circle, and the icon scales up on focus.
                    // No-op on touch.
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .then(if (LocalIsTelevision.current) Modifier.size(48.dp) else Modifier)
                        .tvFocusScale(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.manage),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            // Just the heart, no container (the tonal toggle button squashed it into an oval): a larger
            // filled red heart when favourited, a neutral outline when not. Overlaid top-end so it
            // doesn't disturb the centred icon/name/subtitle column. Only the F-Droid catalogue supports
            // favourites today, so external sources simply pass no callback and no heart shows.
            if (onToggleFavorite != null) {
                IconToggleButton(
                    checked = isFavorite,
                    onCheckedChange = { onToggleFavorite() },
                    // TV: square so the focus halo is a clean circle, and the heart scales up on focus.
                    // No-op on touch.
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .then(if (LocalIsTelevision.current) Modifier.size(48.dp) else Modifier)
                        .then(
                            if (favoriteFocusRequester != null) {
                                Modifier.focusRequester(favoriteFocusRequester)
                            } else {
                                Modifier
                            },
                        )
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
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

/** The version / size / source-code stats row inside [HeroCard], each pair separated by a thin divider.
 *  Any of the three can be absent (external sources have no size figure, some repos have no source-code
 *  link); only the present ones are shown, with dividers only between two actually-shown neighbours. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeroStatsRow(
    version: String?,
    size: String?,
    onSourceCodeClick: (() -> Unit)?,
) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        version?.let {
            HeroStatItem(
                label = stringResource(R.string.version),
                value = it,
                modifier = Modifier.weight(1f),
            )
        }
        if (version != null && (size != null || onSourceCodeClick != null)) {
            VerticalDivider(modifier = Modifier.height(28.dp), color = dividerColor)
        }
        size?.let {
            HeroStatItem(label = stringResource(R.string.size), value = it, modifier = Modifier.weight(1f))
        }
        if (size != null && onSourceCodeClick != null) {
            VerticalDivider(modifier = Modifier.height(28.dp), color = dividerColor)
        }
        onSourceCodeClick?.let { onClick ->
            HeroSourceCodeStatItem(onClick = onClick, modifier = Modifier.weight(1f))
        }
    }
}

/** The "Source code" stat: same label-over-value rhythm as [HeroStatItem] (an icon standing in for the
 *  label), coloured and clickable instead of boxed in a chip — reads as one of the three stats rather
 *  than a separate button bolted onto the row. */
@Composable
fun HeroSourceCodeStatItem(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        // TV only: a soft accent fill behind the focused stat (no-op on touch).
        modifier = modifier.tvFocusFill(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_source_code),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.source_code),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The [HeroCard] footer content shared by both detail screens: an optional info line (installed
 * version / latest APK details) plus an optional "see all versions" link that jumps to the version
 * list further down the page — kept in one place so the F-Droid and external pages can't drift apart
 * on this. Returns null (no footer at all) when there's neither, so the caller can pass it straight
 * through to [HeroCard]'s `footer` slot.
 */
fun heroFooter(
    infoText: String?,
    onViewVersionsClick: (() -> Unit)?,
): (@Composable () -> Unit)? {
    if (infoText == null && onViewVersionsClick == null) return null
    return {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            infoText?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (onViewVersionsClick != null) {
                if (infoText != null) Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        // TV only: a soft accent fill behind the focused link (no-op on touch).
                        .tvFocusFill(MaterialTheme.shapes.small)
                        .clickable(onClick = onViewVersionsClick)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.view_all_versions),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** One label-over-value stat, e.g. "Version" / "1.18_beta". */
@Composable
fun HeroStatItem(label: String, value: String, modifier: Modifier = Modifier) {
    // A touch of side padding so a wrapped two-line value (a long version string, say) doesn't butt
    // right up against the divider to its neighbour.
    Column(modifier = modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            // A long version string (some apps' versionName runs on for 20+ characters) wrapped to a
            // second line instead of being truncated with "…" — the whole point of this stat is
            // showing the real version, so ellipsizing it away defeats the purpose.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

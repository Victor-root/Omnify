package com.looker.droidify.compose.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.looker.droidify.R

/**
 * An app page's version list as one card: a tinted header holding the "Versions" title (with
 * [titleAction] at its end, the pre-release choice of a tracked source), then [header] (the source
 * picker and which one the updates come from, when more than one source offers the app), then the
 * versions themselves as rows of the card ([content]), each opened by a [VersionsCardDivider]. Shared
 * by the catalogue's and the tracked sources' pages, so both read as the same list.
 *
 * [header] spans the card's whole width, so a carousel in it scrolls right to the card's edges: what it
 * holds keeps clear of them with [VersionsCardHeaderPadding].
 */
@Composable
fun VersionsCard(
    modifier: Modifier = Modifier,
    titleAction: (@Composable () -> Unit)? = null,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        shape = VersionsCardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.5.dp, primary.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(versionsCardHeaderColor())
                    .padding(top = 4.dp, bottom = if (header == null) 4.dp else 0.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(start = VersionsCardHeaderPadding + 6.dp, end = VersionsCardHeaderPadding),
                ) {
                    Text(
                        text = stringResource(R.string.versions),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    // The title keeps its own width: a long choice wraps beside it instead.
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        titleAction?.invoke()
                    }
                }
                header?.invoke(this)
            }
            content()
        }
    }
}

/** The tint of a [VersionsCard]'s header, for what is drawn over it to blend in. */
@Composable
fun versionsCardHeaderColor(): Color =
    MaterialTheme.colorScheme.primary.copy(alpha = 0.06f).compositeOver(MaterialTheme.colorScheme.surface)

/** What opens each row of a [VersionsCard], the show-more row included. */
@Composable
fun VersionsCardDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

/**
 * A version row's background: the card's own, except the suggested one ([highlighted]) in a list card
 * ([inList]), tinted with the accent. A version standing on its own marks it with a border instead.
 */
@Composable
fun versionRowColor(highlighted: Boolean, inList: Boolean): Color {
    val surface = MaterialTheme.colorScheme.surface
    return if (highlighted && inList) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.07f).compositeOver(surface)
    } else {
        surface
    }
}

private val VersionsCardShape = RoundedCornerShape(22.dp)

/** How far what a [VersionsCard]'s header holds keeps from the card's edges. */
val VersionsCardHeaderPadding = 10.dp

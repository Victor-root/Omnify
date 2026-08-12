package com.looker.droidify.compose.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.looker.droidify.R

/**
 * A "show more (N) / show less" toggle row, for a list that's collapsed to its first few items (e.g.
 * a version list), so a long list doesn't make the whole page require endless scrolling.
 * [hiddenCount] is the number of items collapsed out of view, shown in the "show more" label; null
 * when the caller only knows there's at least one more rather than exactly how many (a source whose
 * full count is worth a second network fetch of its own, not paid just to size this label, see
 * [com.looker.droidify.compose.externalApps.ExternalAppDetailScreen]'s own version list), which shows
 * a plain "show more" instead of a number that would otherwise undersell what tapping it actually
 * reveals. Collapsing back never needs a count either way.
 */
@Composable
fun ShowMoreRow(hiddenCount: Int?, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusFill(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                expanded -> stringResource(R.string.show_less)
                hiddenCount != null -> stringResource(R.string.show_more_versions_FORMAT, hiddenCount)
                else -> stringResource(R.string.show_more)
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

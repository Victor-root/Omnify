package com.looker.droidify.migration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.components.tvFocusScale

/**
 * Offers the switch to the stable build from the built-in Omnify source's own page, where the update
 * button would otherwise be.
 *
 * The two channels are separate apps to Android, so what is on offer is an install rather than an
 * update (see [ChannelMigration]), and it needs saying: a beta whose update button simply went quiet
 * would read as the source having broken, not as the app having moved.
 *
 * Carries its own button rather than being a tappable block of text like the app's other banners. Those
 * lead somewhere; this one performs the switch, which is worth a real button — a coloured panel with no
 * visible control reads as a warning to acknowledge, not as something to press, and this one was being
 * read exactly that way.
 *
 * Only ever shown from a beta (see ExternalApp.offersStableSwitch): the stable build is where a beta's
 * user is meant to end up. The reverse is not an offer worth making — someone on the stable build is
 * where they belong — so this says one thing and only one thing.
 *
 * Shown by the phone and TV detail screens alike, so both say the same thing in the same words.
 */
@Composable
fun ChannelSwitchBanner(onInstallStable: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.migration_to_stable_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            text = stringResource(R.string.migration_install_stable_DESC),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Button(
            onClick = onInstallStable,
            modifier = Modifier.align(Alignment.End).tvFocusScale(),
        ) { Text(stringResource(R.string.install)) }
    }
}

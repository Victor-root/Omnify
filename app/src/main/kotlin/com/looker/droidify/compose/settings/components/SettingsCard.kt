package com.looker.droidify.compose.settings.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.looker.droidify.compose.components.PremiumCard
import com.looker.droidify.compose.theme.LocalAccentBarColor
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.compose.theme.LocalOnAccentBarColor

/** The rows of one settings card, the key its lazy item takes given with one of them. */
class SettingsCardScope internal constructor() {
    internal var key: Any? = null
    internal val rows = mutableListOf<@Composable () -> Unit>()

    fun row(key: Any? = null, content: @Composable () -> Unit) {
        if (key != null) this.key = key
        rows += content
    }
}

/**
 * A section of settings named [title]: on a touch screen, one card like the app page's own
 * ([PremiumCard]), its name on a band in the accent across its top, with [icon]. A television keeps
 * the name as a plain heading above plain rows, its focus fill marking the row the remote is on.
 */
fun LazyListScope.settingsCard(
    @StringRes title: Int? = null,
    @DrawableRes icon: Int? = null,
    build: SettingsCardScope.() -> Unit,
) {
    val card = SettingsCardScope().apply(build)
    item(key = card.key) {
        if (LocalIsTelevision.current) {
            Column {
                if (title != null) SettingHeader(stringResource(title))
                card.rows.forEach { it() }
            }
        } else {
            PremiumCard(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp)) {
                Column {
                    if (title != null) SettingsCardBand(title, icon)
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                        card.rows.forEach { it() }
                    }
                }
            }
        }
    }
}

/** A settings card's name, on a band in the accent across its top. */
@Composable
private fun SettingsCardBand(@StringRes title: Int, @DrawableRes icon: Int?) {
    val onAccent = LocalOnAccentBarColor.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(LocalAccentBarColor.current)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = onAccent,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = stringResource(title),
            color = onAccent,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}

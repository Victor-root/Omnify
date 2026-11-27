package com.looker.droidify.compose.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.looker.droidify.compose.components.PremiumCard
import com.looker.droidify.compose.theme.LocalIsTelevision

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
 * A section of settings: on a touch screen, one card like the app page's own ([PremiumCard]), white
 * in its accent frame. A television keeps plain rows, its focus fill marking the row the remote is on.
 */
fun LazyListScope.settingsCard(build: SettingsCardScope.() -> Unit) {
    val card = SettingsCardScope().apply(build)
    item(key = card.key) {
        if (LocalIsTelevision.current) {
            Column { card.rows.forEach { it() } }
        } else {
            PremiumCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                    card.rows.forEach { it() }
                }
            }
        }
    }
}

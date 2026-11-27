package com.looker.droidify.compose.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.looker.droidify.compose.theme.LocalIsTelevision

/** The rows of one settings card, each with the key its lazy item takes, if any. */
class SettingsCardScope internal constructor() {
    internal val rows = mutableListOf<Pair<Any?, @Composable () -> Unit>>()

    fun row(key: Any? = null, content: @Composable () -> Unit) {
        rows += key to content
    }
}

/**
 * A section of settings drawn as one rounded card on a touch screen, its rows still a lazy item each so
 * a long section composes row by row as it scrolls in. Each row draws its own slice of the card, the
 * first and last ones its rounded ends. A television keeps plain rows, its focus fill marking the row.
 */
fun LazyListScope.settingsCard(build: SettingsCardScope.() -> Unit) {
    val rows = SettingsCardScope().apply(build).rows
    rows.forEachIndexed { index, (key, content) ->
        item(key = key) {
            SettingsCardSlice(isFirst = index == 0, isLast = index == rows.lastIndex, content = content)
        }
    }
}

private val CardCorner = 28.dp

@Composable
private fun SettingsCardSlice(isFirst: Boolean, isLast: Boolean, content: @Composable () -> Unit) {
    if (LocalIsTelevision.current) {
        content()
        return
    }
    val shape = RoundedCornerShape(
        topStart = if (isFirst) CardCorner else 0.dp,
        topEnd = if (isFirst) CardCorner else 0.dp,
        bottomStart = if (isLast) CardCorner else 0.dp,
        bottomEnd = if (isLast) CardCorner else 0.dp,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
            .padding(start = 8.dp, end = 8.dp, top = if (isFirst) 10.dp else 0.dp, bottom = if (isLast) 10.dp else 4.dp),
    ) {
        content()
    }
}

package com.looker.droidify.compose.settings.hiddenApps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.appList.AppMinimalIcon
import com.looker.droidify.compose.components.BackButton
import com.looker.droidify.compose.components.FloatingAppCardsBackground
import com.looker.droidify.compose.components.forFloatingBackground
import com.looker.droidify.compose.components.tvDpadDownTo
import com.looker.droidify.compose.components.tvFocusFill
import com.looker.droidify.compose.components.tvFocusScale
import com.looker.droidify.compose.externalApps.ExternalAppIcon
import com.looker.droidify.compose.theme.AccentBarHeight
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.compose.theme.accentTopAppBarColors
import com.looker.droidify.compose.tv.TvAccentBackground
import com.looker.droidify.compose.tv.TvAccentHeader

/**
 * Lists every app the user has hidden (catalogue or external), each with an unhide action: the only way
 * back to visibility once an app has been hidden from every other list in the app. Reached from Settings.
 * Renders both phone and TV itself, same convention as
 * [com.looker.droidify.compose.settings.SettingsScreen], rather than a separate Tv screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenAppsScreen(
    viewModel: HiddenAppsViewModel,
    onBackClick: () -> Unit,
) {
    val hiddenApps by viewModel.hiddenApps.collectAsStateWithLifecycle()
    val isTelevision = LocalIsTelevision.current
    // TV / D-pad: drop focus from the header into the list (the top bar won't on its own).
    val contentFocusRequester = remember { FocusRequester() }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isTelevision) TvAccentBackground()
        Scaffold(
            containerColor = if (isTelevision) Color.Transparent else MaterialTheme.colorScheme.background,
            topBar = {
                if (isTelevision) {
                    TvAccentHeader(
                        title = stringResource(R.string.hidden_apps_title),
                        onBackClick = onBackClick,
                        modifier = Modifier.tvDpadDownTo(contentFocusRequester),
                    )
                } else {
                    TopAppBar(
                        colors = accentTopAppBarColors(),
                        expandedHeight = AccentBarHeight,
                        modifier = Modifier.tvDpadDownTo(contentFocusRequester),
                        title = { Text(text = stringResource(R.string.hidden_apps_title)) },
                        navigationIcon = { BackButton(onBackClick) },
                    )
                }
            },
        ) { contentPadding ->
            if (!isTelevision) {
                FloatingAppCardsBackground(Modifier.padding(contentPadding.forFloatingBackground()))
            }
            if (hiddenApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .focusRequester(contentFocusRequester),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.no_hidden_apps),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = contentPadding,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(contentFocusRequester),
                ) {
                    items(hiddenApps, key = { it.key }) { app ->
                        HiddenAppItem(app = app, onUnhide = { viewModel.unhide(app) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HiddenAppItem(app: HiddenApp, onUnhide: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // TV only: a soft accent fill behind the focused row (no-op on touch).
            .tvFocusFill(RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Never the real installed launcher icon here (isInstalled = false): this is a management list,
        // not a browse grid, so the repo/source-declared icon is enough and avoids a second dependency
        // (install state) this screen otherwise has no other use for.
        when (app) {
            is HiddenApp.Catalogue -> AppMinimalIcon(
                app = app.app,
                isInstalled = false,
                modifier = Modifier.size(48.dp),
            )

            is HiddenApp.External -> ExternalAppIcon(app = app.app, isInstalled = false, size = 48.dp)
        }
        Spacer(modifier = Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = when (app) {
                is HiddenApp.Catalogue -> app.app.summary
                is HiddenApp.External -> "${app.app.sourceLabel} · ${app.app.path}"
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
        IconButton(onClick = onUnhide, modifier = Modifier.tvFocusScale()) {
            Icon(
                imageVector = Icons.Filled.Visibility,
                contentDescription = stringResource(R.string.unhide_app),
            )
        }
    }
}

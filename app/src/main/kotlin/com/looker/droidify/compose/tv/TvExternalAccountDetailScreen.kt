package com.looker.droidify.compose.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.components.TvOverscan
import com.looker.droidify.compose.externalApps.ExternalAppIcon
import com.looker.droidify.compose.externalApps.ExternalAppsViewModel

/**
 * The Android TV detail screen for a whole-account external source (e.g. a GitHub user) — the account's
 * name, source and description, then the grid of apps discovered from it, each opening its own detail
 * screen. Same data as the phone screen ([ExternalAppsViewModel]); only the TV layout differs. Never
 * composed off TV.
 */
@Composable
fun TvExternalAccountDetailScreen(
    accountKey: String,
    viewModel: ExternalAppsViewModel,
    onBackClick: () -> Unit,
    onAppClick: (String) -> Unit,
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val installedKeys by viewModel.installedKeys.collectAsStateWithLifecycle()

    BackHandler { onBackClick() }
    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.refreshInstalled()
    }

    val account = accounts.firstOrNull { it.key == accountKey }
    if (account == null) {
        TvCentered { Text(stringResource(R.string.repository_not_found)) }
        return
    }

    val accountApps = remember(apps, accountKey) {
        apps.filter { it.accountKey == accountKey }.sortedBy { it.label.trim().lowercase() }
    }
    val contentFocus = remember { FocusRequester() }
    LaunchedEffect(accountApps.isEmpty()) { runCatching { contentFocus.requestFocus() } }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    TvAccentBackground()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(contentFocus)
            .focusGroup(),
        contentPadding = PaddingValues(TvOverscan + 16.dp),
        horizontalArrangement = spacedBy(18.dp),
        verticalArrangement = spacedBy(18.dp),
    ) {
        // Header spans the whole width; the app cards flow beneath it.
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = spacedBy(12.dp)) {
                TvBackButton(onBackClick)
                Text(
                    text = account.label,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${account.provider.name.lowercase().replaceFirstChar { it.uppercase() }} · ${account.owner}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (account.description.isNotBlank()) {
                    Text(
                        text = account.description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                TvSectionTitle(stringResource(R.string.tab_external))
            }
        }
        gridItems(accountApps, key = { it.key }, contentType = { "tv-account-app" }) { app ->
            TvAppCard(
                name = app.label,
                onClick = { onAppClick(app.key) },
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    ExternalAppIcon(app = app, isInstalled = app.key in installedKeys, size = 96.dp)
                }
            }
        }
    }
    }
}

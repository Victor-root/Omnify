package com.looker.droidify.compose.externalApps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.components.BackButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalAppsScreen(
    viewModel: ExternalAppsViewModel,
    onBackClick: () -> Unit,
) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val installStates by viewModel.installStates.collectAsStateWithLifecycle()
    val installedKeys by viewModel.installedKeys.collectAsStateWithLifecycle()

    // Refresh update + install status whenever the screen is opened.
    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.refreshInstalled()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.external_apps_title)) },
                navigationIcon = { BackButton(onBackClick) },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(
                            painter = painterResource(R.drawable.ic_tabler_refresh),
                            contentDescription = stringResource(R.string.external_check_updates),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(viewModel.snackbarHostState) },
    ) { contentPadding ->
        Column(modifier = Modifier.padding(contentPadding)) {
            AddSourceRow(onAdd = viewModel::addSource)
            HorizontalDivider()
            if (apps.isEmpty()) {
                Text(
                    text = stringResource(R.string.external_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(8.dp)) {
                items(items = apps, key = { it.key }) { app ->
                    ExternalAppCard(
                        app = app,
                        downloadStatus = downloads[app.key],
                        installState = installStates[app.key],
                        isInstalled = app.key in installedKeys,
                        onInstallOrUpdate = { viewModel.installOrUpdate(app) },
                        onLaunch = { viewModel.launch(app) },
                        onUninstall = { viewModel.uninstall(app) },
                        onCancel = { viewModel.cancel(app) },
                        onRemove = { viewModel.remove(app.key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddSourceRow(onAdd: (url: String, includePrereleases: Boolean) -> Unit) {
    var url by remember { mutableStateOf("") }
    var includePrereleases by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.external_source_url_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includePrereleases, onCheckedChange = { includePrereleases = it })
            Text(
                text = stringResource(R.string.external_include_prereleases),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = {
                    onAdd(url, includePrereleases)
                    url = ""
                },
                enabled = url.isNotBlank(),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_tabler_plus),
                    contentDescription = null,
                )
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.external_add))
            }
        }
    }
}

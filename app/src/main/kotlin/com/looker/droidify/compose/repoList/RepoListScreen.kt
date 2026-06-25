package com.looker.droidify.compose.repoList

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.looker.droidify.R
import com.looker.droidify.compose.components.BackButton
import com.looker.droidify.compose.externalApps.ExternalAppIcon
import com.looker.droidify.compose.externalApps.ExternalAppsViewModel
import com.looker.droidify.data.model.Repo
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.utility.text.toAnnotatedString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoListScreen(
    viewModel: RepoListViewModel,
    onRepoClick: (Int) -> Unit,
    onBackClick: () -> Unit,
    onAddRepo: () -> Unit,
) {
    val repos by viewModel.stream.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()

    // The External sources live alongside the F-Droid repos here: this screen is source management
    // (enable / disable / add / remove). The apps themselves (install, launch…) are on the External
    // tab. Both are backed by the same singleton repositories, so the lists stay in sync everywhere.
    val externalViewModel: ExternalAppsViewModel = hiltViewModel()
    val externalApps by externalViewModel.apps.collectAsStateWithLifecycle()
    val externalInstalledKeys by externalViewModel.installedKeys.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        externalViewModel.refreshInstalled()
        externalViewModel.reconcileInstalledLabels()
    }

    var showAddExternal by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(externalViewModel.snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.repositories)) },
                    navigationIcon = { BackButton(onBackClick) },
                    actions = {
                        IconButton(onClick = { showAddExternal = true }) {
                            Icon(
                                painterResource(R.drawable.ic_tabler_package),
                                contentDescription = stringResource(R.string.external_add_source),
                            )
                        }
                        IconButton(onClick = onAddRepo) {
                            Icon(
                                painterResource(R.drawable.ic_tabler_plus),
                                contentDescription = stringResource(R.string.add_repository),
                            )
                        }
                    },
                )
                if (isSyncing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
    ) { contentPadding ->
        LazyColumn(contentPadding = contentPadding) {
            item(key = "external-header") {
                SectionHeader(title = stringResource(R.string.tab_external))
            }
            if (externalApps.isEmpty()) {
                item(key = "external-empty") {
                    Text(
                        text = stringResource(R.string.external_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(externalApps, key = { "ext-${it.key}" }) { app ->
                ExternalSourceItem(
                    app = app,
                    isInstalled = app.key in externalInstalledKeys,
                    onToggle = { externalViewModel.setSourceEnabled(app, !app.enabled) },
                    onRemove = { externalViewModel.remove(app.key) },
                )
            }

            item(key = "repos-header") {
                SectionHeader(title = stringResource(R.string.repo_section_fdroid))
            }
            items(repos, key = { "repo-${it.id}" }) { repo ->
                RepoItem(
                    onClick = { onRepoClick(repo.id) },
                    onToggle = { viewModel.toggleRepo(repo) },
                    repo = repo,
                )
            }
        }
    }

    if (showAddExternal) {
        AddExternalSourceDialog(
            onDismiss = { showAddExternal = false },
            onAdd = { url, includePrereleases ->
                externalViewModel.addSource(url, includePrereleases)
                showAddExternal = false
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun RepoItem(
    onClick: () -> Unit,
    onToggle: () -> Unit,
    repo: Repo,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .then(modifier),
    ) {
        AsyncImage(
            model = repo.icon?.path,
            contentDescription = null,
            colorFilter = if (repo.enabled) null else GrayScaleColorFilter,
            modifier = Modifier
                .size(48.dp)
                .clip(MaterialTheme.shapes.large),
        )
        Spacer(modifier = Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = repo.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (repo.description.isNotEmpty()) {
                Text(
                    text = repo.description.toAnnotatedString { },
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 4,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
        FilledIconToggleButton(
            checked = repo.enabled,
            onCheckedChange = { onToggle() },
        ) {
            Icon(imageVector = Icons.Default.Check, contentDescription = null)
        }
    }
}

/** A tracked external source as a management row: logo, name, owner/repo, an enable/disable toggle
 *  (like a repo) and a remove button. App actions (install, launch…) intentionally live elsewhere,
 *  on the External tab — this row only manages the source. */
@Composable
private fun ExternalSourceItem(
    app: ExternalApp,
    isInstalled: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    val contentAlpha = if (app.enabled) 1f else 0.4f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
        ExternalAppIcon(
            app = app,
            isInstalled = isInstalled,
            size = 48.dp,
            modifier = Modifier.alpha(contentAlpha),
        )
        Spacer(modifier = Modifier.size(16.dp))
        Column(
            modifier = Modifier
                .weight(1F)
                .alpha(contentAlpha),
        ) {
            Text(
                text = app.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${app.provider.label} · ${app.path}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.size(8.dp))
        FilledIconToggleButton(
            checked = app.enabled,
            onCheckedChange = { onToggle() },
        ) {
            Icon(imageVector = Icons.Default.Check, contentDescription = null)
        }
        IconButton(onClick = onRemove) {
            Icon(
                painter = painterResource(R.drawable.ic_tabler_trash),
                contentDescription = stringResource(R.string.external_remove),
            )
        }
    }
}

@Composable
private fun AddExternalSourceDialog(
    onDismiss: () -> Unit,
    onAdd: (url: String, includePrereleases: Boolean) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var includePrereleases by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.external_add_source)) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.external_source_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = includePrereleases,
                        onCheckedChange = { includePrereleases = it },
                    )
                    Text(
                        text = stringResource(R.string.external_include_prereleases),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(url, includePrereleases) },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.external_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

val GrayScaleColorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

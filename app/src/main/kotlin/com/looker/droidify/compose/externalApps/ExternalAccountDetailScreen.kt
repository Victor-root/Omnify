package com.looker.droidify.compose.externalApps

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.components.BackButton
import com.looker.droidify.compose.components.FloatingAppCardsBackground
import com.looker.droidify.compose.components.forFloatingBackground
import com.looker.droidify.compose.components.premiumCardBorder
import com.looker.droidify.compose.components.TvOverscan
import com.looker.droidify.compose.components.tvDpadDownTo
import com.looker.droidify.compose.repoList.AppLauncherIcon
import com.looker.droidify.compose.repoList.RepoIcon
import com.looker.droidify.compose.settings.components.SwitchSettingItem
import com.looker.droidify.compose.theme.AccentBarHeight
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.compose.theme.accentTopAppBarColors
import com.looker.droidify.external.ExternalAccount
import com.looker.droidify.external.ExternalApp

/**
 * Detail screen for a whole-account external source, mirroring the F-Droid [RepoDetailScreen]: an Info
 * tab (avatar, name, link, enable toggle) and an Apps tab that lists every app discovered from the
 * account as the usual tile grid. Single-repo sources don't get this screen (they map to exactly one
 * app, so tapping them opens that app directly) — the app list is only useful for an account.
 *
 * Reuses the shared [ExternalAppsViewModel]: the account and its apps are read from the same singleton
 * repository the sources list and the External tab use, so everything stays in sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalAccountDetailScreen(
    accountKey: String,
    viewModel: ExternalAppsViewModel,
    onBackClick: () -> Unit,
    onAppClick: (String) -> Unit,
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val installedKeys by viewModel.installedKeys.collectAsStateWithLifecycle()

    // Keep release tags / install state current on entry, exactly like the app detail screen.
    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.refreshInstalled()
    }

    val account = accounts.firstOrNull { it.key == accountKey }
    val accountApps = remember(apps, accountKey) {
        apps.filter { it.accountKey == accountKey }.sortedBy { it.label.trim().lowercase() }
    }
    var selectedTab by remember { mutableStateOf(AccountDetailTab.INFO) }

    // TV / D-pad: drop focus from the header (top bar or tab row) into the content below.
    val contentFocusRequester = remember { FocusRequester() }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.tvDpadDownTo(contentFocusRequester, debugLabel = "account-topappbar")) {
                TopAppBar(
                    colors = accentTopAppBarColors(),
                    expandedHeight = AccentBarHeight,
                    title = {
                        Text(
                            text = account?.label.orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = { BackButton(onBackClick) },
                )
                if (account != null) {
                    AccountDetailTabRow(
                        selectedTab = selectedTab,
                        appCount = accountApps.size,
                        onSelectTab = { selectedTab = it },
                    )
                }
            }
        },
    ) { paddingValues ->
        FloatingAppCardsBackground(Modifier.padding(paddingValues.forFloatingBackground()))
        val currentAccount = account
        when {
            currentAccount == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.repository_not_found))
                }
            }

            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .focusRequester(contentFocusRequester)
                        .focusGroup(),
                ) {
                    when (selectedTab) {
                        AccountDetailTab.INFO -> AccountInfoTab(
                            account = currentAccount,
                            appCount = accountApps.size,
                            onToggle = { enabled ->
                                viewModel.setAccountEnabled(currentAccount, enabled)
                            },
                        )

                        AccountDetailTab.APPS -> AccountAppsTab(
                            account = currentAccount,
                            apps = accountApps,
                            installedKeys = installedKeys,
                            onAppClick = onAppClick,
                        )
                    }
                }
            }
        }
    }
}

private enum class AccountDetailTab { INFO, APPS }

// Material3's suggested PrimaryTabRow/SecondaryTabRow replacements default to different container/
// indicator colours than this plain TabRow — since none are overridden here, swapping would risk a
// real look change rather than a mechanical rename.
@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountDetailTabRow(
    selectedTab: AccountDetailTab,
    appCount: Int,
    onSelectTab: (AccountDetailTab) -> Unit,
) {
    TabRow(selectedTabIndex = selectedTab.ordinal) {
        Tab(
            selected = selectedTab == AccountDetailTab.INFO,
            onClick = { onSelectTab(AccountDetailTab.INFO) },
            text = { Text(stringResource(R.string.repo_tab_info)) },
        )
        Tab(
            selected = selectedTab == AccountDetailTab.APPS,
            onClick = { onSelectTab(AccountDetailTab.APPS) },
            text = {
                val label = if (appCount > 0) {
                    "${stringResource(R.string.repo_tab_apps)} ($appCount)"
                } else {
                    stringResource(R.string.repo_tab_apps)
                }
                Text(label)
            },
        )
    }
}

@Composable
private fun AccountInfoTab(
    account: ExternalAccount,
    appCount: Int,
    onToggle: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // The built-in Omnify account is branded with the app's own launcher icon (always correct);
        // every other account uses its avatar (GitHub) or the letter monogram.
        if (account.key == ExternalAccount.OMNIFY_KEY) {
            AppLauncherIcon(modifier = Modifier.size(64.dp))
        } else {
            RepoIcon(
                iconUrl = account.iconUrl,
                fallbackUrl = null,
                name = account.label,
                modifier = Modifier.size(64.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = account.label,
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(modifier = Modifier.height(4.dp))

        val link = remember(account.webUrl) {
            buildAnnotatedString {
                withLink(LinkAnnotation.Url(account.webUrl)) {
                    append(account.webUrl)
                }
            }
        }
        Text(
            text = link,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primaryContainer,
            textDecoration = TextDecoration.Underline,
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (account.description.isNotEmpty()) {
            Text(
                text = account.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            text = "${account.sourceLabel} · ${stringResource(R.string.external_account_apps, appCount)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        val accountCardShape = MaterialTheme.shapes.large
        // See the doc comment on premiumCardBorder's HeroCard usage: the border must live on this
        // outer Box, not inside Surface's own modifier, or its own background paints over it.
        Box(modifier = Modifier.fillMaxWidth().then(premiumCardBorder(accountCardShape))) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = accountCardShape,
                // White instead of a flat bordered grey card — the gradient border above is what
                // ties it to the theme, not a flat colour fill.
                color = MaterialTheme.colorScheme.surface,
            ) {
                SwitchSettingItem(
                    title = stringResource(R.string.repo_enabled_title),
                    description = if (account.enabled) {
                        stringResource(R.string.repo_enabled_desc_on)
                    } else {
                        stringResource(R.string.repo_enabled_desc_off)
                    },
                    checked = account.enabled,
                    onCheckedChange = onToggle,
                )
            }
        }
    }
}

/**
 * The account's discovered apps as the usual tile grid (same [ExternalAppTile] as the External tab), so
 * it reads exactly like every other app list. The empty states mirror the F-Droid repo apps tab: a
 * spinner while a freshly-enabled account is still being scanned, otherwise a neutral "no apps" message.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AccountAppsTab(
    account: ExternalAccount,
    apps: List<ExternalApp>,
    installedKeys: Set<String>,
    onAppClick: (String) -> Unit,
) {
    val isTelevision = LocalIsTelevision.current
    when {
        // Enabled but not scanned yet (e.g. the opt-in Omnify account the moment it's turned on): show a
        // spinner rather than a misleading "no apps".
        apps.isEmpty() && account.enabled && account.lastScan == 0L -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularWavyProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.syncing),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        apps.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.no_applications_available),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        else -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = if (isTelevision) 150.dp else 100.dp),
                // On TV, inset from the screen edges (overscan safe area) so a focused tile's scaled-up
                // highlight near an edge isn't clipped — same inset the main app list uses.
                contentPadding = PaddingValues(if (isTelevision) 12.dp + TvOverscan else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(apps, key = { it.key }) { app ->
                    ExternalAppTile(
                        app = app,
                        isInstalled = app.key in installedKeys,
                        onClick = { onAppClick(app.key) },
                    )
                }
            }
        }
    }
}

package com.looker.droidify.compose.externalApps

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.components.BackButton
import com.looker.droidify.compose.components.DescriptionTranslation
import com.looker.droidify.compose.components.HeroCard
import com.looker.droidify.compose.components.HeroStatsRow
import com.looker.droidify.compose.components.TranslateAction
import com.looker.droidify.compose.components.tvPageScroll
import com.looker.droidify.compose.theme.AccentBarHeight
import com.looker.droidify.compose.theme.accentTopAppBarColors
import com.looker.droidify.external.apkVersionLabel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExternalAppDetailScreen(
    appKey: String,
    viewModel: ExternalAppsViewModel,
    onBackClick: () -> Unit,
) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val installStates by viewModel.installStates.collectAsStateWithLifecycle()
    val installedKeys by viewModel.installedKeys.collectAsStateWithLifecycle()
    val installedVersions by viewModel.installedVersions.collectAsStateWithLifecycle()
    val readme by viewModel.readme.collectAsStateWithLifecycle()
    val readmeError by viewModel.readmeError.collectAsStateWithLifecycle()
    val readmeTranslation by viewModel.readmeTranslation.collectAsStateWithLifecycle()
    val translationEnabled by viewModel.translationEnabled.collectAsStateWithLifecycle()
    val readmeJavaScriptEnabled by viewModel.readmeJavaScriptEnabled.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.reconcileInstalledLabels()
    }
    // Re-query install state on every resume — in particular when returning from the system uninstall
    // dialog — so the buttons switch from Open/Uninstall back to Install without having to leave and
    // re-open the screen (installManager.state alone doesn't report a system uninstall).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshInstalled()
    }

    val app = apps.firstOrNull { it.key == appKey }
    val isInstalled = appKey in installedKeys
    val installedVersion = installedVersions[appKey]
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    // Download the project README (as HTML) once the app is known, to fill out the detail screen.
    LaunchedEffect(app?.key) {
        app?.let { viewModel.loadReadme(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = accentTopAppBarColors(),
                expandedHeight = AccentBarHeight,
                title = {
                    Text(
                        text = app?.label.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton(onBackClick) },
                actions = {
                    if (app != null) {
                        IconButton(
                            onClick = {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    putExtra(Intent.EXTRA_TEXT, app.webUrl)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, null))
                            },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_share),
                                contentDescription = stringResource(R.string.share),
                            )
                        }
                    }
                    // Only offer translation once there's a README to translate and an engine is set.
                    if (translationEnabled && readme != null) {
                        TranslateAction(
                            translation = readmeTranslation,
                            onTranslate = { readme?.let(viewModel::translateReadme) },
                            onShowOriginal = viewModel::showOriginalReadme,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(viewModel.snackbarHostState) },
    ) { contentPadding ->
        if (app == null) {
            // The source was removed (or not loaded yet); nothing to show.
            Spacer(Modifier.padding(contentPadding))
            return@Scaffold
        }
        // Everything (the icon/name/versions/actions block AND the README) scrolls as one — only the
        // top bar stays fixed. The WebView reports its content height (below) so it can be sized
        // exactly instead of owning a second, separate scroll.
        val density = LocalDensity.current
        // Show the translated HTML when present, otherwise the original. Both render in the WebView so a
        // translation keeps the README's images, headings, lists and links.
        val readmeHtml = (readmeTranslation as? DescriptionTranslation.Translated)?.description ?: readme
        // Keyed on app.key (stable for this screen), NOT on the html: the WebView captures its
        // height callback once, so the callback must keep targeting the same state instance. The
        // WebView re-reports its height every time the document reloads, so translating or reverting
        // resizes correctly on its own.
        var readmeHeightPx by remember(app.key) { mutableStateOf(0) }
        // Hoisted so the README can page-scroll it on TV; viewport height drives the page step.
        val scrollState = rememberScrollState()
        var viewportPx by remember { mutableStateOf(0) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .onSizeChanged { viewportPx = it.height }
                .verticalScroll(scrollState),
        ) {
            // The APK file name is the actual build offered (repos version/name it differently from the
            // project's own tag), and the installed version if any — folded into the card's footer,
            // mirroring how the F-Droid catalogue card shows its installed version there.
            val footerText = listOfNotNull(
                app.latestApkName?.let {
                    stringResource(R.string.external_latest_apk, apkVersionLabel(it))
                },
                installedVersion?.let { stringResource(R.string.external_installed_version, it) },
            ).joinToString("\n").ifBlank { null }

            HeroCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                icon = {
                    ExternalAppIcon(app = app, isInstalled = isInstalled, size = 88.dp)
                },
                name = app.label,
                subtitle = stringResource(R.string.by_author_FORMAT, app.owner),
                stats = {
                    HeroStatsRow(
                        version = app.latestTag,
                        size = null,
                        onSourceCodeClick = { uriHandler.openUri(app.webUrl) },
                    )
                },
                actions = {
                    ExternalLifecycleActions(
                        app = app,
                        downloadStatus = downloads[appKey],
                        installState = installStates[appKey],
                        isInstalled = isInstalled,
                        onInstallOrUpdate = { viewModel.installOrUpdate(app) },
                        onLaunch = { viewModel.launch(app) },
                        onUninstall = { viewModel.uninstall(app) },
                        onCancel = { viewModel.cancel(app) },
                    )
                },
                footer = footerText?.let { text ->
                    {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                },
            )

            if (!isInstalled) {
                PreInstallNotice(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()

            // README — sized to its content (it doesn't scroll itself) so it scrolls with the rest.
            // GitHub leaves repo-relative image paths un-rewritten, so the WebView resolves them
            // against the raw content host. While it loads, show a spinner instead of empty space.
            if (readmeHtml != null) {
                // On TV this Box is the single focus stop for the whole README: landing here, the D-pad
                // pages the screen up/down through it (the WebView itself is non-focusable on TV, so the
                // remote no longer steps over its links and images). A plain wrapper on touch.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvPageScroll(scrollState, (viewportPx * 0.85f).toInt()),
                ) {
                    ReadmeWebView(
                        html = readmeHtml,
                        baseUrl = app.readmeWebBaseUrl,
                        javaScriptEnabled = readmeJavaScriptEnabled,
                        onContentHeight = { readmeHeightPx = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            // Until the content height is known, give it a sensible height so it can render
                            // and measure; then snap to the exact height.
                            .height(
                                if (readmeHeightPx > 0) {
                                    with(density) { readmeHeightPx.toDp() }
                                } else {
                                    600.dp
                                },
                            ),
                    )
                }
            } else if (readmeError != null) {
                // The fetch already failed (most often the anonymous GitHub rate limit) — say so instead
                // of leaving the spinner running forever with no way to tell it apart from "still loading".
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = readmeError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularWavyProgressIndicator(modifier = Modifier.size(36.dp))
                }
            }
        }
    }
}

/** Tells the user that the name/icon/version shown are the repository's until the app is installed
 *  (a release carries no app metadata, so the real ones are only known once the APK is on-device). */
@Composable
private fun PreInstallNotice(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Outlined.Info, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.external_preinstall_notice),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

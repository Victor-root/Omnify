package com.looker.droidify.compose.externalApps

import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import com.looker.droidify.compose.components.InstallVersionDialog
import com.looker.droidify.compose.components.LinkRow
import com.looker.droidify.compose.components.ScrollToTopFab
import com.looker.droidify.compose.components.SectionTitle
import com.looker.droidify.compose.components.SupportedLanguagesSection
import com.looker.droidify.compose.components.TranslateAction
import com.looker.droidify.compose.components.heroFooter
import com.looker.droidify.compose.components.tvPageScroll
import com.looker.droidify.compose.theme.AccentBarHeight
import com.looker.droidify.compose.theme.accentTopAppBarColors
import com.looker.droidify.external.Release
import com.looker.droidify.external.apkFileName
import com.looker.droidify.external.apkVersionLabel
import kotlinx.coroutines.launch

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
    val releaseHistory by viewModel.releaseHistory.collectAsStateWithLifecycle()
    val issueTrackerLink by viewModel.issueTrackerLink.collectAsStateWithLifecycle()
    val changelogLink by viewModel.changelogLink.collectAsStateWithLifecycle()
    val supportedLanguages by viewModel.supportedLanguages.collectAsStateWithLifecycle()

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
    // Hoisted above the Scaffold (not inside its content lambda) so both the content column and the
    // scroll-to-top FAB can read/drive the same scroll position.
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    // Where the versions section actually lands once composed (in the scrolling Column's own
    // coordinate space), so the hero card's "see all versions" link can jump straight to it.
    var versionsAnchorY by remember { mutableStateOf(0) }

    // Download the project README (as HTML) once the app is known, to fill out the detail screen.
    LaunchedEffect(app?.key) {
        app?.let { viewModel.loadReadme(it) }
    }
    // Recent releases, for the "choose a version to install" list at the bottom of the screen.
    LaunchedEffect(app?.key) {
        app?.let { viewModel.loadReleaseHistory(it) }
    }
    // The Links section's "Issue tracker" and "Changelog" rows.
    LaunchedEffect(app?.key) {
        app?.let { viewModel.loadIssueTrackerAndChangelog(it) }
    }
    // The "supported languages" section — re-checked when the install state changes too, so it
    // switches from the remote-verified check to the installed APK's own locales right after install.
    LaunchedEffect(app?.key, isInstalled) {
        app?.let { viewModel.loadSupportedLanguages(it, isInstalled) }
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
        floatingActionButton = { ScrollToTopFab(scrollState) },
    ) { contentPadding ->
        if (app == null) {
            // The source was removed (or not loaded yet); nothing to show.
            Spacer(Modifier.padding(contentPadding))
            return@Scaffold
        }
        // A release the user tapped in the version list, awaiting confirmation to install (null = no
        // dialog). Unlike the F-Droid catalogue's version dialog, downgrades are never flagged here: a
        // release carries no version code ahead of the download, so there's nothing to compare against
        // the installed one before the user confirms.
        var versionToInstall by remember { mutableStateOf<Release?>(null) }
        versionToInstall?.let { release ->
            InstallVersionDialog(
                versionName = release.tag,
                isDowngrade = false,
                onInstall = {
                    viewModel.installVersion(app, release)
                    versionToInstall = null
                },
                onUninstall = {},
                onDismiss = { versionToInstall = null },
            )
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
        // scrollState is hoisted above the Scaffold (used by the page-scroll TV modifier below and by
        // the scroll-to-top FAB). viewportPx drives the page step for TV D-pad paging.
        var viewportPx by remember { mutableStateOf(0) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .onSizeChanged { viewportPx = it.height }
                .verticalScroll(scrollState),
        ) {
            // The repo's release tag (e.g. "v2.5.0") often doesn't match the APK's own version — the file
            // name usually does (e.g. "GlassKeep-1.4.6.apk" for a "v2.5.0" release), so that's what's shown
            // as the hero "Version" whenever a build is available, falling back to the tag otherwise.
            val heroVersion = app.latestApkName?.let { apkVersionLabel(it) } ?: app.latestTag

            // The installed version, if any — folded into the card's footer, mirroring how the F-Droid
            // catalogue card shows its installed version there.
            val footerText = installedVersion?.let {
                stringResource(R.string.external_installed_version, it)
            }

            HeroCard(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                icon = {
                    ExternalAppIcon(app = app, isInstalled = isInstalled, size = 88.dp)
                },
                name = app.label,
                subtitle = stringResource(R.string.by_author_FORMAT, app.owner),
                stats = {
                    HeroStatsRow(
                        version = heroVersion,
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
                footer = heroFooter(
                    infoText = footerText,
                    onViewVersionsClick = if (!releaseHistory.isNullOrEmpty()) {
                        { coroutineScope.launch { scrollState.animateScrollTo(versionsAnchorY) } }
                    } else {
                        null
                    },
                ),
            )

            if (!isInstalled) {
                PreInstallNotice(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionSeparator()
            Spacer(Modifier.height(4.dp))

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
                        scrollState = scrollState,
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

            // "Issue tracker" and "Changelog" — an external source has no index metadata for these like
            // the F-Droid catalogue does, so they're resolved live from the provider. Always shown (not
            // hidden while resolving/absent) so the page doesn't jump around as the checks complete;
            // a still-loading or genuinely absent link reads as a plain "…" / explanatory row instead.
            Spacer(Modifier.height(16.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                SectionTitle(stringResource(R.string.links), R.drawable.ic_tabler_link)
                LinkRow(
                    iconRes = R.drawable.ic_bug_report,
                    title = stringResource(R.string.issue_tracker),
                    url = issueTrackerLink?.url,
                    unavailableText = stringResource(
                        if (issueTrackerLink == null) R.string.loading else R.string.external_no_issue_tracker,
                    ),
                    onClick = issueTrackerLink?.url?.let { url -> { uriHandler.openUri(url) } },
                )
                LinkRow(
                    iconRes = R.drawable.ic_history,
                    title = stringResource(R.string.changelog),
                    url = changelogLink?.url,
                    unavailableText = stringResource(
                        if (changelogLink == null) R.string.loading else R.string.external_no_changelog,
                    ),
                    onClick = changelogLink?.url?.let { url -> { uriHandler.openUri(url) } },
                )
            }

            // Same reliable, real-UI-language check as the F-Droid catalogue's — but with no store-
            // listing metadata to fall back to for an external source, this section only shows once
            // there's a confirmed answer, instead of ever showing a guess.
            supportedLanguages?.let { languages ->
                Spacer(Modifier.height(8.dp))
                SupportedLanguagesSection(languages = languages)
            }

            // Recent releases the user can pick a specific version to install from — same idea as the
            // F-Droid catalogue's version list, at the bottom of the page in the same way. Position-
            // tracked so the hero card's "see all versions" link can scroll straight to it.
            if (!releaseHistory.isNullOrEmpty()) {
                Column(
                    modifier = Modifier.onGloballyPositioned {
                        versionsAnchorY = it.positionInParent().y.toInt()
                    },
                ) {
                    Spacer(Modifier.height(20.dp))
                    SectionSeparator()
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.versions),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    releaseHistory.orEmpty().forEach { release ->
                        ReleaseVersionItem(
                            release = release,
                            apkName = release.apkFileName(filter = app.apkFilter),
                            isSuggested = release.tag == app.latestTag,
                            isInstalled = release.tag == app.installedTag,
                            onClick = { versionToInstall = release },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** A short, centred rounded bar between the hero card and the README, instead of a full-width line —
 *  reads as a soft section break rather than a hard rule dividing the page in two. */
@Composable
private fun SectionSeparator(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .width(80.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
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

package com.looker.droidify.compose

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.util.Consumer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.looker.droidify.BuildConfig
import com.looker.droidify.R
import com.looker.droidify.compose.appDetail.navigation.appDetail
import com.looker.droidify.compose.appDetail.navigation.navigateToAppDetail
import com.looker.droidify.compose.appList.navigation.AppList
import com.looker.droidify.compose.appList.navigation.appList
import com.looker.droidify.compose.appList.navigation.navigateToAppList
import com.looker.droidify.compose.components.UnknownSourcesDialog
import com.looker.droidify.compose.home.navigation.home
import com.looker.droidify.compose.repoDetail.navigation.navigateToRepoDetail
import com.looker.droidify.compose.repoDetail.navigation.repoDetail
import com.looker.droidify.compose.repoEdit.navigation.navigateToRepoEdit
import com.looker.droidify.compose.repoEdit.navigation.repoEdit
import com.looker.droidify.compose.externalApps.navigation.externalAppDetail
import com.looker.droidify.compose.externalApps.navigation.navigateToExternalAppDetail
import com.looker.droidify.compose.repoList.navigation.navigateToRepoList
import com.looker.droidify.compose.repoList.navigation.repoList
import com.looker.droidify.compose.settings.navigation.navigateToSettings
import com.looker.droidify.compose.settings.navigation.settings
import com.looker.droidify.compose.theme.DroidifyTheme
import com.looker.droidify.data.AppRepository
import com.looker.droidify.data.RepoRepository
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.external.ExternalAccount
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.ExternalAppRepository
import com.looker.droidify.external.SourceProvider
import com.looker.droidify.datastore.extension.getThemeRes
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.Theme
import com.looker.droidify.installer.InstallManager
import com.looker.droidify.installer.model.installFrom
import com.looker.droidify.model.Repository
import com.looker.droidify.utility.common.DeeplinkType
import com.looker.droidify.utility.common.SdkCheck
import com.looker.droidify.utility.common.sdkAbove
import com.looker.droidify.utility.common.canRequestPackageInstalls
import com.looker.droidify.utility.common.deeplinkType
import com.looker.droidify.utility.common.getInstallPackageName
import com.looker.droidify.utility.common.openUnknownAppSourcesSettings
import com.looker.droidify.utility.common.requestNotificationPermission
import com.looker.droidify.work.SyncWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainComposeActivity : ComponentActivity() {

    @Inject
    lateinit var repository: RepoRepository

    @Inject
    lateinit var appRepository: AppRepository

    @Inject
    lateinit var externalAppRepository: ExternalAppRepository

    @Inject
    lateinit var installer: InstallManager

    companion object {
        const val ACTION_INSTALL = "${BuildConfig.APPLICATION_ID}.intent.action.INSTALL"
        const val ACTION_UPDATES = "${BuildConfig.APPLICATION_ID}.intent.action.UPDATES"
        const val EXTRA_CACHE_FILE_NAME =
            "${BuildConfig.APPLICATION_ID}.intent.extra.CACHE_FILE_NAME"

        private const val FIRST_RUN_PREFS = "first_run"
        private const val KEY_UNKNOWN_SOURCES_PROMPTED = "unknown_sources_prompted"

        // Seeded once on first run: Omnify's own repo as the active update channel, plus the developer's
        // whole GitHub account as a separate, opt-in (disabled) source.
        private const val OMNIFY_SOURCE_OWNER = "Victor-root"
        private const val OMNIFY_SOURCE_REPO = "Omnify"
        private const val KEY_OMNIFY_SEED = "omnify_seed_v4"
    }

    /** Omnify's own repo (github.com/Victor-root/Omnify) as the built-in update channel, active by
     *  default. [packageName] is the *running* build's applicationId (BuildConfig.APPLICATION_ID), so the
     *  source reports the version actually installed — a debug build shows its own version instead of a
     *  stale seeded value or an unrelated stable install. [installedTag] tracks the release channel for
     *  update detection. */
    private fun omnifyUpdateSource(): ExternalApp = ExternalApp(
        provider = SourceProvider.GITHUB,
        owner = OMNIFY_SOURCE_OWNER,
        repo = OMNIFY_SOURCE_REPO,
        label = getString(R.string.application_name),
        packageName = BuildConfig.APPLICATION_ID,
        installedTag = BuildConfig.VERSION_NAME,
        enabled = true,
    )

    /** The developer's whole GitHub account as a second, opt-in source: disabled by default (the user
     *  enables it to get every app of the account), forks included (the apps are published as forks),
     *  labelled with the account name but shown with the app's own logo (see [ExternalAccount.OMNIFY_KEY]).
     *  The Omnify repo above is tracked separately, so the account won't absorb it. */
    private fun victorAccount(): ExternalAccount = ExternalAccount(
        provider = SourceProvider.GITHUB,
        owner = OMNIFY_SOURCE_OWNER,
        label = OMNIFY_SOURCE_OWNER,
        enabled = false,
        includeForks = true,
    )

    private val firstRunPrefs by lazy { getSharedPreferences(FIRST_RUN_PREFS, Context.MODE_PRIVATE) }

    /** True only the first time, and only when the app can't yet install unknown apps — so the user is
     *  prompted once, up front, instead of hitting the permission at their first install. */
    private fun shouldPromptUnknownSources(): Boolean =
        !canRequestPackageInstalls() &&
            !firstRunPrefs.getBoolean(KEY_UNKNOWN_SOURCES_PROMPTED, false)

    private fun markUnknownSourcesPrompted() {
        firstRunPrefs.edit().putBoolean(KEY_UNKNOWN_SOURCES_PROMPTED, true).apply()
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SettingsEntryPoint {
        fun settingsRepository(): SettingsRepository
    }

    private data class ThemeState(
        val theme: Theme,
        val dynamicTheme: Boolean,
        val themeColor: Int,
        val edgeToEdge: Boolean,
    )

    /**
     * Reads the current theme/accent settings, applies the matching XML theme, and re-creates the
     * activity whenever they change. Uses an entry point (not field injection) because the value is
     * needed before super.onCreate().
     */
    private fun collectThemeChanges(): ThemeState {
        val entryPoint = EntryPointAccessors.fromApplication(this, SettingsEntryPoint::class.java)
        val themeFlow = entryPoint.settingsRepository()
            .get { ThemeState(theme, dynamicTheme, themeColor, edgeToEdge) }
        val initial = runBlocking { themeFlow.first() }
        setTheme(
            resources.configuration.getThemeRes(
                theme = initial.theme,
                dynamicTheme = initial.dynamicTheme,
            ),
        )
        lifecycleScope.launch {
            themeFlow.drop(1).collect { recreate() }
        }
        return initial
    }

    /**
     * Generates an MD3 palette from the chosen accent color and applies it to this activity, so the
     * Compose screens follow the user's color. Skipped when Material You is on (S+).
     */
    private fun applyAccentColor(state: ThemeState) {
        if (state.dynamicTheme && SdkCheck.isSnowCake) return
        val options = DynamicColorsOptions.Builder()
            .setContentBasedSource(state.themeColor)
            .build()
        DynamicColors.applyToActivityIfAvailable(this, options)
    }

    /** Routes an incoming deeplink/intent to the matching Compose destination. */
    private fun handleDeeplink(intent: Intent, navController: NavController) {
        try {
            when (intent.action) {
                ACTION_INSTALL -> {
                    val packageName = intent.getInstallPackageName
                    val cacheFileName = intent.getStringExtra(EXTRA_CACHE_FILE_NAME)
                    if (!packageName.isNullOrEmpty() && !cacheFileName.isNullOrEmpty()) {
                        navController.navigateToAppDetail(packageName)
                        lifecycleScope.launch {
                            installer.install(packageName installFrom cacheFileName)
                        }
                    }
                }

                ACTION_UPDATES -> navController.navigateToAppList()

                Intent.ACTION_VIEW -> when (val deeplink = intent.deeplinkType()) {
                    is DeeplinkType.AppDetail -> navController.navigateToAppDetail(deeplink.packageName)
                    is DeeplinkType.AppSearch -> navController.navigateToAppList()
                    // TODO: pre-fill the repo address once RepoEdit accepts one.
                    is DeeplinkType.AddRepository -> navController.navigateToRepoEdit()
                    null -> Unit
                }

                Intent.ACTION_SHOW_APP_INFO -> {
                    val packageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
                    if (!packageName.isNullOrEmpty()) navController.navigateToAppDetail(packageName)
                }
            }
        } catch (_: Exception) {
            // Malformed deeplink or nav graph not ready yet — ignore rather than crash.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeState = collectThemeChanges()
        super.onCreate(savedInstanceState)
        applyAccentColor(themeState)
        // Always draw edge-to-edge at the window level (reliable on every version, including the
        // Android 15+ forced enforcement). The "Edge-to-edge" setting is honoured purely in Compose:
        // when off, DroidifyTheme paints an opaque accent bar over the navigation bar so it looks like
        // a solid coloured bar; when on, the app shows through the transparent bars.
        enableEdgeToEdge()
        // Stop the system tinting the bars: under edge-to-edge it enforces a translucent contrast scrim
        // on 3-button navigation, which darkened our accent navigation-bar overlay so it no longer
        // matched the header. We colour the bars ourselves, so opt out and keep the exact accent.
        sdkAbove(Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        // Off the main thread: this seeds repos and queries the catalog/installed state, and the
        // start-up frame is already busy — doing DB work here would jank the UI (and starve the
        // WorkManager scheduler that has to dispatch the sync).
        lifecycleScope.launch(Dispatchers.Default) {
            if (repository.repos.first().isEmpty()) {
                Repository.defaultRepositories.forEach {
                    repository.insertRepo(it.address, it.fingerprint, null, null, it.name, it.description)
                }
            }
            // Enable (and therefore sync) the repos that are enabled by default but aren't yet, so a
            // fresh install fills its catalog automatically instead of showing an empty list.
            val enabledByDefault = Repository.defaultRepositories
                .filter { it.enabled }
                .map { it.address }
                .toSet()
            repository.repos.first()
                .filter { it.address in enabledByDefault && !it.enabled }
                .forEach { repository.enableRepository(it, enable = true) }

            // One-time: seed Omnify's own repo as the active update channel, plus the developer's whole
            // GitHub account as a separate, opt-in (disabled) source. Guarded by a flag (not by "is the
            // list empty"), so if the user removes either it stays removed. Clean up the previous
            // whole-account-enabled seed shape (its discovered apps) before re-seeding.
            if (!firstRunPrefs.getBoolean(KEY_OMNIFY_SEED, false)) {
                externalAppRepository.removeAppsByAccount(ExternalAccount.OMNIFY_KEY)
                // upsert (not add): the Omnify source already exists from an earlier seed, and addApp is
                // insert-only, so it would keep the stale packageName/version. Replace it so the corrected
                // fields (running build's package) actually take effect.
                externalAppRepository.upsertApp(omnifyUpdateSource())
                externalAppRepository.upsertAccount(victorAccount())
                firstRunPrefs.edit().putBoolean(KEY_OMNIFY_SEED, true).apply()
            }

            // Self-heal an empty catalog. A schema migration recreates the database: the repo rows
            // are re-seeded but the catalog isn't, and because a repo's enabled flag lives in
            // DataStore (which survives the reset) the re-seeded repos can look "already enabled" —
            // so the filter above syncs nothing and the list would stay blank until the next 12 h
            // periodic sync. When there are enabled repos but no apps, kick one full sync now.
            if (appRepository.appCount() == 0 && repository.getEnabledRepos().first().isNotEmpty()) {
                SyncWorker.enqueueUserSync(this@MainComposeActivity)
            }
        }
        requestNotificationPermission(request = notificationPermission::launch)
        setContent {
            val darkTheme = when (themeState.theme) {
                Theme.DARK, Theme.AMOLED -> true
                Theme.LIGHT -> false
                Theme.SYSTEM, Theme.SYSTEM_BLACK -> isSystemInDarkTheme()
            }
            DroidifyTheme(
                darkTheme = darkTheme,
                dynamicColor = themeState.dynamicTheme,
                accentColor = themeState.themeColor,
                edgeToEdge = themeState.edgeToEdge,
            ) {
                val navController = rememberNavController()
                // Handle the launching deeplink, then any that arrive while we're running.
                LaunchedEffect(navController) {
                    handleDeeplink(intent, navController)
                }
                DisposableEffect(navController) {
                    val listener = Consumer<Intent> { newIntent ->
                        handleDeeplink(newIntent, navController)
                    }
                    addOnNewIntentListener(listener)
                    onDispose { removeOnNewIntentListener(listener) }
                }
                // Each destination has its own Scaffold + TopAppBar that handle system-bar
                // insets, so this outer Scaffold must NOT add its own (it would double the
                // top inset and leave a large empty gap above every screen's title).
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                ) { innerPadding ->
                    NavHost(
                        modifier = Modifier.padding(innerPadding),
                        navController = navController,
                        startDestination = AppList,
                    ) {
                        home(
                            onNavigateToApps = { navController.navigateToAppList() },
                            onNavigateToRepos = { navController.navigateToRepoList() },
                            onNavigateToSettings = { navController.navigateToSettings() },
                        )
                        appList(
                            onAppClick = { packageName ->
                                navController.navigateToAppDetail(packageName)
                            },
                            onExternalAppClick = { appKey ->
                                navController.navigateToExternalAppDetail(appKey)
                            },
                            onNavigateToRepos = { navController.navigateToRepoList() },
                            onNavigateToSettings = { navController.navigateToSettings() },
                        )

                        repoList(
                            onRepoClick = { repoId -> navController.navigateToRepoDetail(repoId) },
                            onBackClick = { navController.popBackStack() },
                            onAddRepo = { navController.navigateToRepoEdit() },
                        )

                        externalAppDetail(onBackClick = { navController.popBackStack() })

                        appDetail(
                            onBackClick = { navController.popBackStack() },
                        )

                        repoDetail(
                            onBackClick = { navController.popBackStack() },
                            onEditClick = { repoId ->
                                navController.navigateToRepoEdit(repoId)
                            },
                        )

                        repoEdit(onBackClick = { navController.popBackStack() })

                        settings(onBackClick = { navController.popBackStack() })
                    }
                }
                // First-run: offer to allow installing apps up front, so the permission isn't hit
                // later at the user's first install. Shown once (persisted), only when not granted.
                var promptUnknownSources by remember { mutableStateOf(shouldPromptUnknownSources()) }
                if (promptUnknownSources) {
                    UnknownSourcesDialog(
                        onAllow = {
                            markUnknownSourcesPrompted()
                            promptUnknownSources = false
                            openUnknownAppSourcesSettings()
                        },
                        onDismiss = {
                            markUnknownSourcesPrompted()
                            promptUnknownSources = false
                        },
                    )
                }
            }
        }
    }
}

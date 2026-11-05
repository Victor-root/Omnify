package com.looker.droidify.external

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.looker.droidify.BuildConfig
import com.looker.droidify.data.InstalledRepository
import com.looker.droidify.di.ApplicationScope
import com.looker.droidify.installer.InstallManager
import com.looker.droidify.utility.common.extension.installerSourceLabel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the package manager says about the tracked sources.
 *
 * Shared by every screen rather than read by each one's ViewModel: answering means a few package manager
 * calls per source, and a source's page has to know as it opens whether its app is installed. Starting
 * from nothing, every page first showed an uninstalled app, and asked the network what only an
 * uninstalled app needs to.
 */
@Singleton
class ExternalInstalledState @Inject constructor(
    repository: ExternalAppRepository,
    installManager: InstallManager,
    installedRepository: InstalledRepository,
    private val externalRefresher: ExternalRefresher,
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    /** Bumped to re-query the package manager (e.g. when a screen is reopened). */
    private val installedRefresh = MutableStateFlow(0)

    // Emits on any install/uninstall on the device (kept up to date by InstalledAppReceiver). Using it
    // as a trigger makes install-state react to the authoritative package-change broadcast, so an
    // uninstall from a source's page updates the button without a resume-timing race.
    private val installedChanges = installedRepository.getAllStream()

    /** Per-app versionName of whatever is installed under its package name (read from the package
     *  manager), keyed by [ExternalApp.key], this source's own build or another one: what the detail
     *  screen names when warning that another build holds the package ([signatureMismatches]). */
    val deviceVersions: StateFlow<Map<String, String>> = combine(
        repository.apps,
        installManager.state,
        installedRefresh,
        installedChanges,
    ) { apps, _, _, _ ->
        apps.mapNotNull { app ->
            val pkg = app.packageName
            if (pkg == null) {
                if (BuildConfig.TRACES) Log.d(TAG, "installedVersions ${app.key}: no packageName on record")
                return@mapNotNull null
            }
            val version = externalRefresher.installedVersionName(pkg)
            if (version == null) {
                if (BuildConfig.TRACES) {
                    Log.d(TAG, "installedVersions ${app.key}: pkg=$pkg but not found by the package manager")
                }
                return@mapNotNull null
            }
            app.key to version
        }.toMap()
    }.distinctUntilChanged().flowOn(Dispatchers.Default).shared()

    /**
     * Keys ([ExternalApp.key]) whose package name is held on the device by another build than the
     * source's own: one none of the source's APK signers signed ([ExternalApp.isForeignCopy]), such as
     * Google's own Gboard next to a source publishing a modified one under the same package name. A
     * package name alone isn't proof of identity. The value is whether that installed app is a system
     * app, i.e. can't actually be uninstalled. A key is absent while either signer is unknown.
     */
    val signatureMismatches: StateFlow<Map<String, Boolean>> = combine(
        repository.apps,
        deviceVersions,
    ) { apps, versions ->
        apps.mapNotNull { app ->
            val pkg = app.packageName?.takeIf { app.key in versions && app.latestApkSigners.isNotEmpty() }
                ?: return@mapNotNull null
            // The copy this source installed itself is its own build, whatever key signed it: a
            // development build installed on purpose is often signed with another than the releases'.
            if (app.ownsInstalled(versions[app.key])) return@mapNotNull null
            if (!app.isForeignCopy(externalRefresher.installedSigner(pkg))) return@mapNotNull null
            app.key to isSystemApp(pkg)
        }.toMap()
    }.distinctUntilChanged().flowOn(Dispatchers.Default).shared()

    /** Per-app real installed versionName of the source's own build, keyed by [ExternalApp.key]: so the
     *  detail shows the version actually on the device vs. the repo's. Another build holding the package
     *  name ([signatureMismatches]) isn't this app installed, and is left out. */
    val installedVersions: StateFlow<Map<String, String>> = combine(deviceVersions, signatureMismatches) {
            versions, mismatches ->
        versions - mismatches.keys
    }.distinctUntilChanged().shared()

    /** Where each installed tracked app actually came from (Play, F-Droid, this app…), keyed by
     *  [ExternalApp.key], shown next to the installed version so a mismatch with what Omnify expects
     *  (e.g. a copy installed by another client, which can't be updated across signing keys in place)
     *  is visible instead of silently offering "Launch" with no explanation. Derived from
     *  [deviceVersions] so a key only ever appears here once something is installed under it. */
    val installSources: StateFlow<Map<String, String>> = combine(
        deviceVersions,
        repository.apps,
    ) { versions, apps ->
        val byKey = apps.associateBy { it.key }
        versions.mapNotNull { (key, version) ->
            val app = byKey[key] ?: return@mapNotNull null
            val pkg = app.packageName ?: return@mapNotNull null
            // Android can lose track of its own "who installed this" record for a package that was
            // genuinely installed through Omnify. Observed after fully uninstalling and reinstalling
            // Omnify itself, even though the tracked app was never touched. installedVersionName is only
            // ever written once ExternalInstaller.awaitAndRecordInstall confirms a real install, so it
            // matching the version actually on the device right now is Omnify's own proof of having put
            // it there, used as a fallback rather than showing "unknown source" for an app it demonstrably
            // installed.
            val knownInstalledByOmnify = app.installedVersionName != null && app.installedVersionName == version
            key to context.installerSourceLabel(pkg, knownInstalledByOmnify)
        }.toMap()
    }.distinctUntilChanged().flowOn(Dispatchers.Default).shared()

    fun refresh() {
        installedRefresh.value++
    }

    /** True when [packageName] is a system app (or an update to one). Those can't be uninstalled, so a
     *  differently-signed release can never replace them, so there's no point offering to. */
    fun isSystemApp(packageName: String): Boolean = runCatching {
        val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
        (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }.getOrDefault(false)

    private fun <T> Flow<Map<String, T>>.shared(): StateFlow<Map<String, T>> =
        stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())
}

private const val TAG = "ExternalInstalledState"
private const val STOP_TIMEOUT_MS = 5_000L

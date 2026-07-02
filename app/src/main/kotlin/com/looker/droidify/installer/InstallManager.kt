package com.looker.droidify.installer

import android.content.Context
import android.util.Log
import com.looker.droidify.data.InstalledRepository
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.InstallerType
import com.looker.droidify.installer.installers.Installer
import com.looker.droidify.installer.installers.LegacyInstaller
import com.looker.droidify.installer.installers.root.RootInstaller
import com.looker.droidify.installer.installers.session.SessionInstaller
import com.looker.droidify.installer.installers.shizuku.ShizukuInstaller
import com.looker.droidify.installer.model.InstallItem
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.utility.common.Constants
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.extension.addAndCompute
import com.looker.droidify.utility.common.extension.filter
import com.looker.droidify.utility.common.extension.notificationManager
import com.looker.droidify.utility.common.extension.updateAsMutable
import com.looker.droidify.utility.common.log
import com.looker.droidify.utility.notifications.createInstallNotification
import com.looker.droidify.utility.notifications.installNotification
import com.looker.droidify.utility.notifications.removeInstallNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class InstallManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val installedRepository: InstalledRepository,
) {

    private val installItems = Channel<InstallItem>()
    private val uninstallItems = Channel<PackageName>()

    /**
     * Installs waiting for their currently-installed (differently-signed) copy to be uninstalled
     * first, keyed by package name -> release cache file. Android can't update across signers in
     * place, so [reinstall] uninstalls the old copy and parks the new APK here; [reinstaller] installs
     * it once the package actually disappears. Concurrent: written by callers, read by the watcher.
     */
    private val pendingReinstalls = ConcurrentHashMap<String, String>()

    /**
     * Jobs of installs that are currently being processed, keyed by package name. Used so that
     * [cancel] can interrupt an in-flight install (not just a pending one). Accessed from both the
     * install loop and arbitrary callers (UI/receiver), hence concurrent.
     */
    private val installJobs = ConcurrentHashMap<String, Job>()

    val state = MutableStateFlow<Map<PackageName, InstallState>>(emptyMap())

    private var _installer: Installer? = null
        set(value) {
            field?.close()
            field = value
        }
    private val installer: Installer get() = _installer!!

    private val lock = Mutex()
    private val skipSignature = settingsRepository.get { ignoreSignature }
    private val installerPreference = settingsRepository.get { installerType }
    private val deleteApkPreference = settingsRepository.get { deleteApkOnInstall }
    private val notificationManager by lazy { context.notificationManager }

    suspend operator fun invoke() = coroutineScope {
        setupInstaller()
        installer()
        uninstaller()
        reinstaller()
    }

    fun close() {
        _installer = null
        uninstallItems.close()
        installItems.close()
    }

    suspend infix fun install(installItem: InstallItem) {
        installItems.send(installItem)
    }

    suspend infix fun uninstall(packageName: PackageName) {
        uninstallItems.send(packageName)
    }

    /**
     * Updates [packageName] across a signing-key change: Android can't replace a differently-signed
     * app in place, so this uninstalls the installed copy and, once it's gone, installs [installFileName]
     * (the already-downloaded, verified new APK). This is the same uninstall-then-reinstall the app
     * page offers, but driven from code so a batch "update all" can carry it out without a per-app
     * dialog of its own — the only prompt is the system's own uninstall confirmation.
     */
    suspend fun reinstall(packageName: PackageName, installFileName: String) {
        pendingReinstalls[packageName.name] = installFileName
        uninstall(packageName)
    }

    infix fun remove(packageName: PackageName) {
        updateState { remove(packageName) }
    }

    /**
     * Cancels an install for [packageName]. If the install is already running it cancels the
     * underlying job/session; if it is only pending it is marked as failed so it is skipped when it
     * reaches the head of the queue. Either way the task always lands in a visible, terminal state
     * instead of being silently stuck.
     */
    infix fun cancel(packageName: PackageName) {
        log("Cancel requested: ${packageName.name}", LIFECYCLE_TAG, Log.INFO)
        val runningJob = installJobs[packageName.name]
        if (runningJob != null) {
            runningJob.cancel(CancellationException("Install cancelled by user"))
        } else if (state.value.containsKey(packageName)) {
            updateState { put(packageName, InstallState.Failed) }
        }
    }

    infix fun setFailed(packageName: PackageName) {
        updateState { put(packageName, InstallState.Failed) }
    }

    private fun CoroutineScope.setupInstaller() = launch {
        installerPreference.collectLatest(::setInstaller)
    }

    private fun CoroutineScope.installer() = launch {
        val installScope = this
        val currentQueue = mutableSetOf<String>()
        installItems.filter { item ->
            currentQueue.addAndCompute(item.packageName.name) { isAdded ->
                if (isAdded) {
                    log("Enqueue: ${item.packageName.name}", LIFECYCLE_TAG, Log.INFO)
                    updateState { put(item.packageName, InstallState.Pending) }
                }
            }
        }.consumeEach { item ->
            val name = item.packageName.name
            // Each install runs in its own child job so it can be cancelled individually without
            // tearing down the whole queue, and is joined so installs stay strictly sequential.
            val job = installScope.launch { processItem(item) }
            installJobs[name] = job
            try {
                job.join()
            } finally {
                installJobs.remove(name)
                // Always free the dedup slot, even on cancellation/failure, otherwise re-enqueuing
                // the same package would be silently dropped forever.
                currentQueue.remove(name)
            }
        }
    }

    /**
     * Installs a single item. This function is hardened so that the install loop can never get
     * stuck: every exit path (success, failure, timeout, cancellation, unexpected exception) ends
     * with the package in a terminal [InstallState] and its install notification cleared.
     */
    private suspend fun processItem(item: InstallItem) {
        val name = item.packageName.name
        // The package may have been cancelled/removed while it was still pending.
        if (state.value[item.packageName] != InstallState.Pending) {
            log("Skip (no longer pending): $name", LIFECYCLE_TAG, Log.INFO)
            return
        }
        var result = InstallState.Failed
        try {
            updateState { put(item.packageName, InstallState.Installing) }
            runCatching {
                notificationManager?.installNotification(
                    packageName = name,
                    notification = context.createInstallNotification(
                        appName = name,
                        state = InstallState.Installing,
                    ),
                )
            }
            log("Start install: $name", LIFECYCLE_TAG, Log.INFO)
            // safeInstall converts every backend failure/timeout into Failed and only re-throws
            // cancellation, so an install can never silently hang the queue.
            result = safeInstall(INSTALL_TIMEOUT) { installer.use { it.install(item) } }
            log("Installer result: $name -> $result", LIFECYCLE_TAG, Log.INFO)
            if (result == InstallState.Installed) {
                // Post-install bookkeeping must never break the queue. A failure here (e.g. a
                // CursorWindow NO_MEMORY while loading the updates list) used to kill the whole
                // consumer; now it is isolated and logged.
                runCatching { onInstallSucceeded(item) }.onFailure {
                    log("Post-install handling failed: $name -> ${it.message}", LIFECYCLE_TAG, Log.ERROR)
                }
            }
        } catch (e: CancellationException) {
            log("Cancel completed: $name", LIFECYCLE_TAG, Log.WARN)
            throw e
        } finally {
            // Run under NonCancellable so the terminal state is always recorded, even when this
            // install was cancelled mid-flight.
            withContext(NonCancellable) {
                notificationManager?.removeInstallNotification(name)
                // Only update if the package is still tracked, so an explicit remove() is respected.
                updateState { if (containsKey(item.packageName)) put(item.packageName, result) }
                log("Cleanup: $name -> $result", LIFECYCLE_TAG, Log.INFO)
            }
        }
    }

    private suspend fun onInstallSucceeded(item: InstallItem) {
        if (installer !is LegacyInstaller && deleteApkPreference.first()) {
            val apkFile = Cache.getReleaseFile(context, item.installFileName)
            apkFile.delete()
        }
    }

    private fun CoroutineScope.uninstaller() = launch {
        uninstallItems.consumeEach {
            installer.uninstall(it)
        }
    }

    /**
     * Watches the installed-apps table and, when a package parked in [pendingReinstalls] disappears
     * (its old copy finished uninstalling), installs the new APK that was waiting for it. Removing the
     * entry before enqueuing means a duplicate table emission can't install it twice.
     */
    private fun CoroutineScope.reinstaller() = launch {
        installedRepository.getAllStream().collect { installed ->
            if (pendingReinstalls.isEmpty()) return@collect
            val installedNames = installed.mapTo(HashSet()) { it.packageName }
            for (name in pendingReinstalls.keys.toList()) {
                if (name in installedNames) continue
                val fileName = pendingReinstalls.remove(name) ?: continue
                log("Reinstall after uninstall: $name", LIFECYCLE_TAG, Log.INFO)
                install(InstallItem(PackageName(name), fileName))
            }
        }
    }

    private suspend fun setInstaller(installerType: InstallerType) {
        lock.withLock {
            _installer = when (installerType) {
                InstallerType.LEGACY -> LegacyInstaller(context, settingsRepository)
                InstallerType.SESSION -> SessionInstaller(context)
                InstallerType.SHIZUKU -> ShizukuInstaller(context)
                InstallerType.ROOT -> RootInstaller(context)
            }
        }
    }

    private inline fun updateState(block: MutableMap<PackageName, InstallState>.() -> Unit) {
        state.update { it.updateAsMutable(block) }
    }

    companion object {
        private const val LIFECYCLE_TAG = "InstallManager"

        /**
         * Upper bound for a single install. Installs normally finish in seconds; this only acts as a
         * safety net for backends that never report a result (e.g. a lost PackageInstaller callback
         * on some OEM ROMs, or a Shizuku session hanging) so the queue can always make progress.
         */
        private val INSTALL_TIMEOUT: Duration = 10.minutes
    }
}

package com.looker.droidify.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.looker.droidify.BuildConfig
import com.looker.droidify.data.model.AppMinimal
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.SortOrder
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.ExternalAppRepository
import com.looker.droidify.external.ExternalRefresher
import com.looker.droidify.external.releaseVersionLabel
import com.looker.droidify.utility.notifications.UpdateEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PendingUpdates"

/** Everything with an update waiting at one moment: the catalogue half as package names (the rows to
 *  show are read for them where they are shown), the external half as the sources themselves. */
data class PendingUpdateSet(
    val cataloguePackages: Set<String>,
    val external: List<ExternalApp>,
) {
    /** How many apps are waiting, whatever a screen's own search or filter happens to show of them. */
    val count: Int get() = cataloguePackages.size + external.size
}

/**
 * What currently has an update waiting, catalogue and external sources alike: the one place that
 * answers it, for the "updates available" notification and for the Updates tab alike.
 *
 * Both used to work it out on their own. The tab combined four flows that each start from an empty
 * placeholder, so for as long as they took to answer it showed an empty list that looked exactly like
 * "everything is up to date", while the notification, built from fresh reads, named real updates.
 * Everything here is read fresh, and [stream] re-reads it whenever one of its inputs changes, so a
 * screen can show the same answer a worker asks for once.
 *
 * Both halves defer to the shared predicates the rules live in: [hasCatalogueUpdate] and
 * [ExternalApp.isUpdatePending].
 */
@Singleton
class PendingUpdates @Inject constructor(
    private val appRepository: AppRepository,
    private val installedRepository: InstalledRepository,
    private val externalAppRepository: ExternalAppRepository,
    private val externalRefresher: ExternalRefresher,
    private val settingsRepository: SettingsRepository,
    @param:ApplicationContext private val context: Context,
) {

    /**
     * [cataloguePackages] and [externalApps], again whenever something either reads changes: the
     * catalogue (a sync), the installed apps, the tracked sources, or which apps the user hid.
     *
     * Those flows only say that something changed: what to do about it is read fresh each time, so a
     * placeholder can never be mistaken for an answer. The first value is therefore a real one, and
     * nothing is emitted before it: a collector that has not received one yet is still waiting, which is
     * different from there being nothing to update.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val stream: Flow<PendingUpdateSet> = combine(
        appRepository.throttledCatalogChanges,
        installedRepository.getAllStream(),
        externalAppRepository.apps,
        settingsRepository.get { hiddenApps }.distinctUntilChanged(),
    ) { _, _, _, _ -> }
        .mapLatest {
            PendingUpdateSet(cataloguePackages(), externalApps()).also { pending ->
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "pending: ${pending.cataloguePackages} + ${pending.external.map { it.key }}")
                }
            }
        }
        .distinctUntilChanged()

    /**
     * Installed catalogue apps with a newer device-compatible build available, carrying the name and
     * offered version so a caller can list them to the user without querying again.
     *
     * Apps the user hid are left out, so this matches the Updates tab rather than quietly acting on
     * something that was deliberately taken off every listing. So are apps an external source installed
     * and still accounts for, for the same reason and rather more urgently: what the Updates tab merely
     * shows, this installs on its own, and a version code that means one thing to the catalogue and
     * another to the source it really came from is how that becomes a silent downgrade.
     */
    suspend fun catalogueApps(): List<AppMinimal> {
        val updatable = cataloguePackages()
        if (updatable.isEmpty()) return emptyList()
        return appRepository.apps(sortOrder = SortOrder.NAME, packageNames = updatable.toList())
    }

    /** The package names [catalogueApps] lists: the catalogue half of everything waiting. */
    suspend fun cataloguePackages(): Set<String> {
        val hidden = settingsRepository.get { hiddenApps }.first()
        val suggested = appRepository.suggestedVersions()
        val installedApps = installedRepository.getAllStream().first()
        val externallyOwned = externallySourcedPackages(
            installedSigners = installedApps.associate { it.packageName to it.signature },
            suggested = suggested,
        )
        return installedApps
            .filter { installed ->
                installed.packageName !in hidden &&
                    hasCatalogueUpdate(
                        installedVersionCode = installed.versionCode,
                        installedVersionName = installed.version,
                        installedSigner = installed.signature,
                        isSystemApp = isSystemApp(installed.packageName),
                        installedFromExternalSource = installed.packageName in externallyOwned,
                        suggested = suggested[installed.packageName],
                    )
            }
            .mapTo(mutableSetOf()) { it.packageName }
    }

    /**
     * Installed packages a tracked external source, not the catalogue, is responsible for, by the same
     * rule the Updates tab reads ([externalSourceOwns]).
     *
     * Disabled and muted sources count too, since turning a source off says nothing about where the copy
     * on the device came from. The installed version is read live from the package manager rather than
     * trusted from the source's own record, for the reason [externalApps] gives below.
     */
    private suspend fun externallySourcedPackages(
        installedSigners: Map<String, String>,
        suggested: Map<String, SuggestedVersion>,
    ): Set<String> = externalAppRepository.getApps()
        .mapNotNullTo(mutableSetOf()) { app ->
            val pkg = app.packageName ?: return@mapNotNullTo null
            if (pkg !in installedSigners) return@mapNotNullTo null
            val owns = externalSourceOwns(
                ownsInstalled = app.ownsInstalled(externalRefresher.installedVersionName(pkg)),
                installedSigner = installedSigners[pkg],
                catalogueSigners = suggested[pkg]?.signers.orEmpty(),
            )
            pkg.takeIf { owns }
        }

    /**
     * External sources with a newer release than the copy actually on the device.
     *
     * The installed version is read live from the package manager rather than from the source's own
     * record, for the same reason the Updates tab does it: a record can be out of step with reality
     * (installed before the source was tracked, or an install that never really landed), and
     * [ExternalApp.isUpdatePending] uses the live value to settle it. A source that isn't installed at
     * all therefore yields nothing here: automatic updates update, they never install something new.
     */
    suspend fun externalApps(): List<ExternalApp> {
        val hidden = settingsRepository.get { hiddenApps }.first()
        return externalAppRepository.getApps().filter { app ->
            if (app.key in hidden) return@filter false
            val onDevice = app.packageName?.let(externalRefresher::installedVersionName)
            app.isUpdatePending(onDevice)
        }
    }

    /**
     * Everything waiting, both halves together, as the name and version pairs a notification lists.
     * Catalogue first, then external sources, each already in its own order.
     */
    suspend fun allAsEntries(): List<UpdateEntry> {
        val catalogue = catalogueApps().map { UpdateEntry(it.name, it.suggestedVersion) }
        val external = externalApps().map {
            UpdateEntry(it.label, releaseVersionLabel(it.latestApkName, it.latestTag))
        }
        return catalogue + external
    }

    private fun isSystemApp(packageName: String): Boolean = runCatching {
        val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
        (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }.getOrDefault(false)
}

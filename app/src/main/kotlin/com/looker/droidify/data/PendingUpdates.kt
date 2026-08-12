package com.looker.droidify.data

import android.content.Context
import android.content.pm.ApplicationInfo
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
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What currently has an update waiting, catalogue and external sources alike, resolved without a
 * ViewModel so a worker can ask the same question the Updates tab answers on screen.
 *
 * The Updates tab computes its own lists reactively from flows, which is the right shape for a screen
 * but unusable from a headless worker. Rather than restate the rules here (and risk the automatic
 * installer acting on a different set than the one the user was shown), both halves defer to the same
 * shared predicates the tab uses: [hasCatalogueUpdate] and [ExternalApp.isUpdatePending].
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
     * Installed catalogue apps with a newer device-compatible build available, carrying the name and
     * offered version so a caller can list them to the user without querying again.
     *
     * Apps the user hid are left out, so this matches the Updates tab rather than quietly acting on
     * something that was deliberately taken off every listing.
     */
    suspend fun catalogueApps(): List<AppMinimal> {
        val hidden = settingsRepository.get { hiddenApps }.first()
        val suggested = appRepository.suggestedVersions()
        val updatable = installedRepository.getAllStream().first()
            .filter { installed ->
                installed.packageName !in hidden &&
                    hasCatalogueUpdate(
                        installedVersionCode = installed.versionCode,
                        installedSigner = installed.signature,
                        isSystemApp = isSystemApp(installed.packageName),
                        suggested = suggested[installed.packageName],
                    )
            }
            .mapTo(mutableSetOf()) { it.packageName }
        // Reading the catalogue back is what turns package ids into names and versions, and it is the
        // one expensive step here, so it is skipped entirely on the normal case of nothing to update.
        if (updatable.isEmpty()) return emptyList()
        return appRepository.apps(sortOrder = SortOrder.NAME)
            .filter { it.packageName.name in updatable }
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

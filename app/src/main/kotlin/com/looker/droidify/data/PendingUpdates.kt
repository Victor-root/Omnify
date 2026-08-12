package com.looker.droidify.data

import android.content.Context
import android.content.pm.ApplicationInfo
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.ExternalAppRepository
import com.looker.droidify.external.ExternalRefresher
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
     * Package names of installed catalogue apps with a newer device-compatible build available.
     *
     * Apps the user hid are left out, so this matches the Updates tab rather than quietly acting on
     * something that was deliberately taken off every listing.
     */
    suspend fun cataloguePackages(): List<String> {
        val hidden = settingsRepository.get { hiddenApps }.first()
        val suggested = appRepository.suggestedVersions()
        return installedRepository.getAllStream().first()
            .filter { installed ->
                installed.packageName !in hidden &&
                    hasCatalogueUpdate(
                        installedVersionCode = installed.versionCode,
                        installedSigner = installed.signature,
                        isSystemApp = isSystemApp(installed.packageName),
                        suggested = suggested[installed.packageName],
                    )
            }
            .map { it.packageName }
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

    private fun isSystemApp(packageName: String): Boolean = runCatching {
        val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
        (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }.getOrDefault(false)
}

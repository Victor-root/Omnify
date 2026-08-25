package com.looker.droidify.migration

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.looker.droidify.BuildConfig
import com.looker.droidify.data.backup.BackupRepository
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.utility.common.extension.getPackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves an install from the beta channel to the stable one.
 *
 * Android identifies an app by its applicationId alone, and the beta build carries a ".beta" suffix on
 * its own, so the two channels are unrelated apps: a stable build installs *beside* a beta rather than
 * over it, and nothing crosses on its own. Left alone that plays out badly, since Omnify's built-in
 * update source would offer the stable build as an ordinary update and install a second copy of the
 * app — automatically, for anyone with automatic updates on. [ExternalApp.offersOtherReleaseChannel]
 * stops that from being treated as an update; this is what takes its place.
 *
 * Two things, then, one per side of the switch:
 *  - on the beta, [MigrationProvider] hands over this install's data on request;
 *  - on the stable build, [importFromBeta] asks for it, and [uninstallBeta] clears the old app away.
 */
@Singleton
class ChannelMigration @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backupRepository: BackupRepository,
) {

    /** The other channel's applicationId, derived from the running build's own: the stable build's from
     *  a beta, the beta's from the stable build. */
    private val otherChannelPackage: String
        get() = if (ExternalApp.RUNNING_BUILD_IS_BETA) {
            BuildConfig.APPLICATION_ID.removeSuffix(".beta")
        } else {
            "${BuildConfig.APPLICATION_ID}.beta"
        }

    /**
     * Whether the other channel's app is sitting on this device next to this one.
     *
     * Answers what each side needs to know, which is not the same thing: for the stable build, that
     * there is data still to collect; for a beta, that the stable build is already there and the user
     * should be sent to it rather than left here.
     *
     * A build whose own id is what the derivation produced never counts as finding anything, which is
     * what keeps a debug build walking through the switch (see the build file) from finding itself.
     */
    fun otherChannelInstalled(): Boolean {
        val packageName = otherChannelPackage
        if (packageName == BuildConfig.APPLICATION_ID) return false
        return context.packageManager.getPackageInfoCompat(packageName) != null
    }

    /** Opens the stable app. Called from a beta once the stable build is installed, since from that
     *  point everything left to do happens over there and nothing more happens here. */
    fun launchStable() {
        val intent = context.packageManager.getLaunchIntentForPackage(otherChannelPackage) ?: return
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "Couldn't open $otherChannelPackage", it) }
    }

    /**
     * Asks the beta install for its data and applies it here.
     *
     * Goes through exactly the archive the file export and the device-to-device transfer already use,
     * and applies it through the same restore, so this path can't quietly diverge from the two that are
     * already exercised. It also means the beta is free to change its own storage from release to
     * release: what crosses is the archive format, not its internals.
     */
    suspend fun importFromBeta(): Result<Unit> = runCatching {
        val packageName = otherChannelPackage
        val authority = "$packageName${MigrationProvider.AUTHORITY_SUFFIX}"
        val result = context.contentResolver.call(
            "content://$authority".toUri(),
            MigrationProvider.METHOD_EXPORT,
            null,
            null,
        ) ?: error("Omnify Beta returned nothing")
        val archive = result.getByteArray(MigrationProvider.KEY_ARCHIVE)
            ?: error("Omnify Beta returned no data")
        val inspection = backupRepository.inspectBackupBytes(archive).getOrThrow()
        // Everything the archive turned out to hold: this is the user's own data coming across from
        // their own install, not a file of unknown provenance, so there is nothing here to pick from.
        backupRepository.restoreBackup(inspection, inspection.availableCategories).getOrThrow()
    }.onFailure {
        Log.w(TAG, "Import from the beta install failed", it)
    }

    /** Whether the user has already put the migration prompt away. Kept in the same small preference
     *  file the first-run flags use, since it is that same kind of one-off marker and has no business
     *  in the settings the user can export and restore onto another device. */
    fun dismissed(): Boolean = preferences.getBoolean(KEY_DISMISSED, false)

    fun setDismissed() {
        preferences.edit { putBoolean(KEY_DISMISSED, true) }
    }

    /** Sends the user to Android's own uninstall prompt for the beta app. Nothing is removed without
     *  them confirming it there, and this is deliberately the last step: the data has already been
     *  brought across by then. */
    fun uninstallBeta() {
        val packageName = otherChannelPackage
        runCatching {
            context.startActivity(
                @Suppress("DEPRECATION")
                Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                    data = "package:$packageName".toUri()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.onFailure { Log.w(TAG, "Couldn't open the uninstall prompt for $packageName", it) }
    }

    private val preferences by lazy {
        context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
    }

    private companion object {
        const val TAG = "ChannelMigration"
        const val MIGRATION_PREFS = "channel_migration"
        const val KEY_DISMISSED = "dismissed"
    }
}

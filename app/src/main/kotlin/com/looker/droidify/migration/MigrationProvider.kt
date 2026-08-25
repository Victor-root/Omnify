package com.looker.droidify.migration

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import androidx.core.os.bundleOf
import com.looker.droidify.data.backup.BackupCategory
import com.looker.droidify.data.backup.BackupRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking

/**
 * Hands this install's data to the other release channel, so switching from the beta to the stable
 * build keeps everything instead of starting from an empty app.
 *
 * The two channels are separate apps to Android (see the beta build type's applicationIdSuffix), which
 * is exactly why this exists: the stable build cannot inherit anything, it has to come and ask. What is
 * handed over is the same archive the file export and the device-to-device transfer already produce,
 * rather than any internal state, so a beta can go on changing its own storage across releases without
 * the migration path drifting out of step with it.
 *
 * Declared only in the beta build's manifest (src/beta/AndroidManifest.xml): the beta is the side being
 * migrated away from, and a stable build has nobody to hand anything to. That keeps this off the stable
 * app entirely rather than leaving an exported component there for a job that is already done.
 *
 * The archive carries repository passwords and the GitHub token, so every call is refused unless the
 * caller is signed with the same key as this app. That is the whole of the trust model, and it is the
 * right one here: only builds of Omnify carry that signature, and anyone who had the key could publish
 * updates outright, so there is nothing this could leak that would still be theirs to protect.
 */
class MigrationProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // Not ContentProvider's own requireContext(): that one only exists from API 30, and this app
        // runs from 23.
        val providerContext = context ?: error("Provider has no context")
        requireSameSignature(providerContext)
        if (method != METHOD_EXPORT) return null
        val backupRepository = EntryPointAccessors
            .fromApplication(providerContext, MigrationEntryPoint::class.java)
            .backupRepository()
        // Binder call, never the main thread; the export is a few kilobytes of settings, well inside
        // what a Bundle carries.
        val archive = runBlocking {
            backupRepository.createBackupBytes(BackupCategory.entries.toSet()).getOrNull()
        } ?: return null
        return bundleOf(KEY_ARCHIVE to archive)
    }

    /** The caller must be another build of this same app. [Binder.getCallingUid] is the kernel's word
     *  on who is calling, not anything the caller states about itself, so this cannot be spoofed. */
    private fun requireSameSignature(context: Context) {
        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.myUid()) return
        val match = context.packageManager.checkSignatures(callingUid, Process.myUid())
        if (match != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("Caller $callingUid is not signed with this app's key")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MigrationEntryPoint {
        fun backupRepository(): BackupRepository
    }

    companion object {
        /** Authority suffix appended to the serving build's applicationId, so the beta's provider is
         *  addressed at "<its applicationId>.migration". Matches src/beta/AndroidManifest.xml. */
        const val AUTHORITY_SUFFIX = ".migration"

        /** Asks for this install's data as a backup archive, under [KEY_ARCHIVE]. */
        const val METHOD_EXPORT = "export"

        const val KEY_ARCHIVE = "archive"
    }
}

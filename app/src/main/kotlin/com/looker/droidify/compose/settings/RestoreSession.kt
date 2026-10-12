package com.looker.droidify.compose.settings

import android.net.Uri
import com.looker.droidify.R
import com.looker.droidify.data.backup.BackupCategory
import com.looker.droidify.data.backup.BackupInspection
import com.looker.droidify.data.backup.BackupRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A restore from a backup file, in its two steps: reading the file, which fills [pending] so the UI can
 * offer a checkbox list scoped to what the archive actually holds, then applying the categories that
 * were kept, leaving the rest alone. Shared by Settings and the welcome pages, so both behave the same.
 *
 * Tells the person how it went through [showMessage], which takes a string resource.
 */
class RestoreSession(
    private val backupRepository: BackupRepository,
    private val scope: CoroutineScope,
    private val showMessage: (Int) -> Unit,
) {
    private val _pending = MutableStateFlow<BackupInspection?>(null)

    /** The archive that has been read and awaits the choice of categories; null when there is none. */
    val pending: StateFlow<BackupInspection?> = _pending.asStateFlow()

    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    /** Reads [uri] (an `OpenDocument`-picked file) and, on success, fills [pending]. Says so and leaves
     *  [pending] untouched if the file isn't a readable Omnify backup. */
    fun inspect(uri: Uri) {
        scope.launch {
            backupRepository.inspectBackup(uri).fold(
                onSuccess = { inspection -> _pending.value = inspection },
                onFailure = { showMessage(R.string.file_format_error_DESC) },
            )
        }
    }

    /** Applies exactly [categories] from the archive [pending] holds, then clears it, and calls
     *  [onRestored] if it worked. Ignored while a restore is already running, rather than starting a
     *  second one on top of it. */
    fun confirm(categories: Set<BackupCategory>, onRestored: () -> Unit = {}) {
        val inspection = _pending.value ?: return
        if (_isRestoring.value) return
        _isRestoring.value = true
        scope.launch {
            backupRepository.restoreBackup(inspection, categories).fold(
                onSuccess = {
                    showMessage(R.string.restore_success)
                    onRestored()
                },
                onFailure = { showMessage(R.string.restore_failed) },
            )
            _isRestoring.value = false
            _pending.value = null
        }
    }

    /** Drops the pending archive without applying anything. Ignored while a restore is running: that
     *  choice was already made by confirming. */
    fun cancel() {
        if (_isRestoring.value) return
        _pending.value = null
    }
}

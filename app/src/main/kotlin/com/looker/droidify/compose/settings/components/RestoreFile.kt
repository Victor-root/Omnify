package com.looker.droidify.compose.settings.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.settings.RestoreSession

// Some file managers report a zip as application/octet-stream or application/x-zip-compressed instead
// of application/zip — accept all three so a real backup file is never greyed out in the picker
// (mirrors the same file-manager MIME-detection quirk the old per-category JSON import worked around).
private val RESTORE_MIME_TYPES = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream",
)

/** Returns the action that opens the file picker, and hands the file picked to [session]. Nothing
 *  happens when the picker is closed without a file. */
@Composable
fun rememberRestoreFilePicker(session: RestoreSession): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) session.inspect(uri) }
    return { launcher.launch(RESTORE_MIME_TYPES) }
}

/** The checkbox dialog that asks which categories of the file read by [session] to apply, shown for as
 *  long as there is one. [onRestored] is called once they have been applied. */
@Composable
fun RestoreCategoriesDialog(session: RestoreSession, onRestored: () -> Unit = {}) {
    val pending by session.pending.collectAsStateWithLifecycle()
    val isRestoring by session.isRestoring.collectAsStateWithLifecycle()
    val inspection = pending ?: return
    BackupCategoryDialog(
        title = stringResource(R.string.restore_dialog_title),
        confirmLabel = stringResource(R.string.restore_title),
        availableCategories = inspection.availableCategories,
        onConfirm = { categories -> session.confirm(categories, onRestored) },
        onDismiss = session::cancel,
        isProcessing = isRestoring,
    )
}

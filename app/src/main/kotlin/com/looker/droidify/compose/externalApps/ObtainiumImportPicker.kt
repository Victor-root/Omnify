package com.looker.droidify.compose.externalApps

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

// File managers label a .json export inconsistently, so the plain text and the generic types are let
// through too: a real export is never greyed out in the picker, and the import itself says when a file
// is not one.
private val EXPORT_MIME_TYPES = arrayOf(
    "application/json",
    "text/plain",
    "application/octet-stream",
)

/** Returns the action that opens the file picker for an Obtainium export, and imports the file picked
 *  (see [ExternalAppsViewModel.importObtainium]). Nothing happens when no file is picked. */
@Composable
fun rememberObtainiumImportPicker(viewModel: ExternalAppsViewModel): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.importObtainium(uri) }
    return { launcher.launch(EXPORT_MIME_TYPES) }
}

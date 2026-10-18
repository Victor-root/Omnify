package com.looker.droidify.compose.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.utility.common.SdkCheck
import com.looker.droidify.utility.common.extension.copyToClipboard
import com.looker.droidify.utility.diagnostics.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The end of the log shown on screen: enough to read what just happened. Copy and Share always take
 *  all of it. Kept short on purpose: a dialog measures its text once at a width of zero, where every
 *  character is a line of its own, and Compose cannot lay out a text taller than 262,143 pixels (a first
 *  version showing 64K characters crashed there), so 8K characters at about 20 pixels a line stay well
 *  under it. */
private const val SHOWN_CHARS = 8 * 1024

/**
 * The diagnostic log ([DiagnosticLog]) with what can be done with it: clear it before reproducing a bug,
 * copy it, or share it to send along with a report. Opens on its most recent lines.
 */
@Composable
fun DiagnosticLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var log by remember { mutableStateOf<String?>(null) }
    // Bumped by Clear, so the log is read again.
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) {
        log = withContext(Dispatchers.IO) { DiagnosticLog.read() }
    }
    val emptyText = stringResource(R.string.diagnostics_empty)
    val scrollState = rememberScrollState()
    LaunchedEffect(log) { scrollState.scrollTo(scrollState.maxValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diagnostics_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.diagnostics_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = log?.ifEmpty { emptyText }?.tail(SHOWN_CHARS).orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(scrollState),
                )
            }
        },
        // The flow row under a dialog wraps its buttons, so the four fit on a narrow phone too.
        dismissButton = {
            TextButton(
                onClick = {
                    DiagnosticLog.clear()
                    version++
                },
            ) { Text(stringResource(R.string.diagnostics_clear)) }
            TextButton(
                onClick = {
                    context.copyToClipboard(DiagnosticLog.read())
                    // Android 13+ shows its own toast for a clipboard write.
                    if (!SdkCheck.isTiramisu) {
                        Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !log.isNullOrEmpty(),
            ) { Text(stringResource(R.string.diagnostics_copy)) }
            TextButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, DiagnosticLog.read())
                    }
                    context.startActivity(Intent.createChooser(send, null))
                },
                enabled = !log.isNullOrEmpty(),
            ) { Text(stringResource(R.string.share)) }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
    )
}

/** The last [count] characters, starting at the beginning of a line so the first one is not cut. */
private fun String.tail(count: Int): String {
    if (length <= count) return this
    val cut = indexOf('\n', length - count)
    return if (cut < 0) takeLast(count) else substring(cut + 1)
}

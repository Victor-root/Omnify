package com.looker.droidify.compose.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
 * copy it, or share it to send along with a report. Opens on its most recent lines. Its own layout rather
 * than an AlertDialog, so the log gets the room a row of buttons and a confirm button would take.
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

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.diagnostics_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                }
                Column(modifier = Modifier.padding(end = 16.dp)) {
                    Text(
                        text = stringResource(R.string.diagnostics_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = log?.ifEmpty { emptyText }?.tail(SHOWN_CHARS).orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(scrollState),
                    )
                }
                Row(modifier = Modifier.fillMaxWidth().padding(end = 16.dp)) {
                    TextButton(
                        onClick = {
                            DiagnosticLog.clear()
                            version++
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.diagnostics_clear), maxLines = 1) }
                    TextButton(
                        onClick = {
                            context.copyToClipboard(DiagnosticLog.read())
                            // Android 13+ shows its own toast for a clipboard write.
                            if (!SdkCheck.isTiramisu) {
                                Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !log.isNullOrEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.diagnostics_copy), maxLines = 1) }
                    TextButton(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, DiagnosticLog.read())
                            }
                            context.startActivity(Intent.createChooser(send, null))
                        },
                        enabled = !log.isNullOrEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.share), maxLines = 1) }
                }
            }
        }
    }
}

/** The last [count] characters, starting at the beginning of a line so the first one is not cut. */
private fun String.tail(count: Int): String {
    if (length <= count) return this
    val cut = indexOf('\n', length - count)
    return if (cut < 0) takeLast(count) else substring(cut + 1)
}

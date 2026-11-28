package com.looker.droidify.compose.components

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.core.text.HtmlCompat
import com.looker.droidify.R
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.model.TranslationEngine
import com.looker.droidify.translation.TranslationManager
import com.looker.droidify.translation.translateHtml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The "Translate" toggle of a whole HTML page (a README, a changelog): the translated page is carried in
 * [DescriptionTranslation.Translated.description]. [label] names the page in the log when it fails.
 */
class PageTranslation(
    private val scope: CoroutineScope,
    private val translationManager: TranslationManager,
    private val settingsRepository: SettingsRepository,
    private val context: Context,
    private val label: String,
) {
    private val _state = MutableStateFlow<DescriptionTranslation>(DescriptionTranslation.Original)
    val state: StateFlow<DescriptionTranslation> = _state.asStateFlow()

    private var job: Job? = null

    /** Translates [html] into the device language. Never throws: a failure is reported and leaves the
     *  toggle in the "failed" state (tapping again retries). */
    fun translate(html: String) {
        if (html.isBlank()) return
        job?.cancel()
        job = scope.launch { translateNow(html, source = null, notifyError = true) }
    }

    /** When the auto-translate setting is on, translates [html], but only if it is in another language
     *  than the device's (detected on-device), so a page already in the user's language is left alone. */
    fun autoTranslate(html: String) {
        if (html.isBlank() || _state.value != DescriptionTranslation.Original) return
        job?.cancel()
        job = scope.launch {
            val settings = settingsRepository.getInitial()
            if (settings.translationEngine == TranslationEngine.NONE || !settings.autoTranslate) return@launch
            val detected = runCatching {
                translationManager.detectLanguage(htmlPlainText(html))
            }.getOrNull()
            if (detected != null && detected != "und" && detected != Locale.getDefault().language) {
                translateNow(html, source = detected, notifyError = false)
            }
        }
    }

    /** Back to the original, dropping a translation still on its way. */
    fun showOriginal() {
        job?.cancel()
        _state.value = DescriptionTranslation.Original
    }

    private suspend fun translateNow(html: String, source: String?, notifyError: Boolean) {
        _state.value = DescriptionTranslation.Loading
        val target = Locale.getDefault().language
        _state.value = runCatching { translationManager.translateHtml(html, target, source) }.fold(
            onSuccess = { DescriptionTranslation.Translated(summary = "", description = it) },
            onFailure = { error ->
                if (error is CancellationException) throw error
                // The message the user gets can only ever say "it didn't work", so the reason has to go
                // somewhere: without this a failing engine, a missing model or a language it doesn't
                // support all look identical from outside.
                Log.w(TAG, "$label translation failed ($source -> $target)", error)
                if (notifyError) Toast.makeText(context, R.string.translation_failed, Toast.LENGTH_SHORT).show()
                DescriptionTranslation.Failed
            },
        )
    }
}

/** [html] as plain text, for translating or detecting its language. */
fun htmlPlainText(html: String): String =
    HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()

private const val TAG = "PageTranslation"

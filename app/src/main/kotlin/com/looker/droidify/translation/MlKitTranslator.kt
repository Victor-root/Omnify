package com.looker.droidify.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device translation via Google ML Kit. Works fully offline once the language pair's model is
 * downloaded — and that download happens here, on the first translation the user asks for, never
 * ahead of time. The source language is auto-detected (falling back to English). Throws when the
 * device locale isn't a language ML Kit supports.
 */
@Singleton
class MlKitTranslator @Inject constructor() {
    suspend fun translate(text: String, targetLanguage: String): String {
        val target = TranslateLanguage.fromLanguageTag(targetLanguage)
            ?: error("ML Kit does not support the language '$targetLanguage'")
        val source = detectLanguage(text)?.let { TranslateLanguage.fromLanguageTag(it) }
            ?: TranslateLanguage.ENGLISH
        // Already in the target language — nothing to do.
        if (source == target) return text

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        val translator = Translation.getClient(options)
        return try {
            // Downloads the (~30 MB) model only if it isn't present yet — triggered by the user's tap,
            // not pre-fetched.
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            translator.translate(text).await()
        } finally {
            translator.close()
        }
    }

    /** ML Kit's best guess of [text]'s language as a BCP-47 tag (e.g. "en"), "und" when unsure, or null
     *  on failure. Uses the bundled language-id model — instant, offline, no download. Also used (engine-
     *  independently) to decide whether auto-translation is needed. */
    suspend fun detectLanguage(text: String): String? = runCatching {
        val client = LanguageIdentification.getClient()
        try {
            client.identifyLanguage(text).await()
        } finally {
            client.close()
        }
    }.getOrNull()
}

/** Awaits a Play-services [Task] from a coroutine without the extra coroutines-play-services library. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> cont.resume(result) }
    addOnFailureListener { exception -> cont.resumeWithException(exception) }
    addOnCanceledListener { cont.cancel() }
}

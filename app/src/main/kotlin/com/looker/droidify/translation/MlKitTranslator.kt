package com.looker.droidify.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val lock = Mutex()
    private var open: OpenTranslator? = null

    /** Reused rather than built per call: the language-id model ships with the app, so the cost here is
     *  purely setting the client up and tearing it down, which used to happen once per translated run. */
    private val languageIdentifier by lazy { LanguageIdentification.getClient() }

    private class OpenTranslator(
        val source: String,
        val target: String,
        val translator: Translator,
    )

    /**
     * Translates [text] into [targetLanguage]. [sourceLanguage] is the language the caller already
     * knows the whole document is in; pass it whenever the text is one piece of a larger translation,
     * since detecting per piece is both wasted work and unreliable (a run as short as "OK" or a version
     * number gets identified as almost anything, and each different answer means a different model).
     * Null falls back to detecting from [text] itself.
     */
    suspend fun translate(text: String, targetLanguage: String, sourceLanguage: String? = null): String {
        val target = TranslateLanguage.fromLanguageTag(targetLanguage)
            ?: error("ML Kit does not support the language '$targetLanguage'")
        val source = (sourceLanguage ?: detectLanguage(text))
            ?.let { TranslateLanguage.fromLanguageTag(it) }
            ?: TranslateLanguage.ENGLISH
        // Already in the target language — nothing to do.
        if (source == target) return text
        // The held translator is one shared native resource, so translations queue rather than overlap.
        // That is no loss on device (a single model, one piece of text at a time either way) and it is
        // what makes it safe to keep: nothing can close a translator another caller is still using.
        return lock.withLock {
            val translator = openTranslator(source, target)
            try {
                translator.translate(text).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A kept translator can stop working on its own: ML Kit closes it from under us when
                // its own guard fires, and every call after that fails with "Translator has been
                // closed". Since this one is held for as long as the process lives, that would break
                // translation everywhere, in every screen, until the app was restarted. Throwing the
                // bad one away means the next attempt builds a fresh one instead.
                discard(translator)
                throw e
            }
        }
    }

    /** Forgets [translator], so the next translation opens a new one. The caller holds [lock]. */
    private fun discard(translator: Translator) {
        if (open?.translator === translator) open = null
        runCatching { translator.close() }
    }

    /**
     * The translator for this language pair, opened once and then kept. The caller holds [lock].
     *
     * It used to be built and closed around every single call, and a translated README is not one call:
     * its text is cut into runs and sent in pieces, so a page meant dozens or hundreds of them. Each one
     * paid for a fresh client, a model availability check and a full model load, then threw the loaded
     * model away again, which is where the minute a translation took actually went. Holding the model
     * costs memory, which is the point: that is what makes every piece after the first one immediate.
     * Only one pair is ever held, swapped (and the previous one closed) when a different pair is asked
     * for, which in practice means when the user changes device language.
     */
    private suspend fun openTranslator(source: String, target: String): Translator {
        val current = open
        if (current != null && current.source == source && current.target == target) {
            return current.translator
        }
        current?.translator?.close()
        open = null
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build(),
        )
        try {
            // Downloads the (~30 MB) model only if it isn't present yet, and only because the user
            // asked for a translation, never ahead of time.
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        } catch (e: Exception) {
            // Nothing is holding it yet, so it would leak its native memory otherwise. Cancellation
            // comes through here too, which is exactly when closing it matters most.
            translator.close()
            throw e
        }
        open = OpenTranslator(source, target, translator)
        return translator
    }

    /** ML Kit's best guess of [text]'s language as a BCP-47 tag (e.g. "en"), "und" when unsure, or null
     *  on failure. Uses the bundled language-id model — instant, offline, no download. Also used (engine-
     *  independently) to decide whether auto-translation is needed. */
    suspend fun detectLanguage(text: String): String? = runCatching {
        languageIdentifier.identifyLanguage(text).await()
    }.getOrNull()
}

/** Awaits a Play-services [Task] from a coroutine without the extra coroutines-play-services library. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> cont.resume(result) }
    addOnFailureListener { exception -> cont.resumeWithException(exception) }
    addOnCanceledListener { cont.cancel() }
}

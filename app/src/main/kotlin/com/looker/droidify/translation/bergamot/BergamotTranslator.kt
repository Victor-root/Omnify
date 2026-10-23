package com.looker.droidify.translation.bergamot

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.duckduckgo.translatekit.ModelSpec
import com.duckduckgo.translatekit.TranslateKit
import com.duckduckgo.translatekit.TranslationModel
import com.looker.droidify.BuildConfig
import com.looker.droidify.utility.diagnostics.DiagnosticLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether this device can run [BergamotTranslator]: translate-kit ships 64-bit libraries only, and the
 * engine needs Android 9 (it calls `glob()` and `iconv()`, which Android only has from there).
 */
fun bergamotSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && Process.is64Bit()

/**
 * On-device translation with Mozilla's engine (Bergamot, the one behind Firefox's offline translation)
 * through DuckDuckGo's translate-kit, with no Google code anywhere: free software throughout, and
 * nothing leaves the device but the one-time download of each language pair's model ([BergamotModels]).
 *
 * The engine translates HTML itself, keeping its tags where they belong, so a whole description or README
 * goes in one call rather than cut into pieces. Language detection runs on the bundled CLD2.
 */
@Singleton
class BergamotTranslator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val models: BergamotModels,
) {
    /** The engine and its models are not thread-safe: one translation at a time. */
    private val lock = Mutex()

    /** The models of the last route, kept loaded: loading one is what a translation would otherwise
     *  pay for every time. */
    private val loaded = mutableMapOf<Pair<String, String>, TranslationModel>()

    suspend fun translate(text: String, targetLanguage: String, sourceLanguage: String?, isHtml: Boolean): String =
        withContext(Dispatchers.IO) {
            start()
            val source = sourceLanguage ?: lock.withLock { detect(text) } ?: ENGLISH
            val route = models.route(source, targetLanguage)
            lock.withLock {
                val startedAt = if (BuildConfig.DIAGNOSTICS) SystemClock.elapsedRealtime() else 0L
                val wanted = route.map { it.source to it.target }.toSet()
                (loaded.keys - wanted).forEach { loaded.remove(it)?.close() }
                val translated = route.fold(text) { current, files ->
                    model(files).translate(current, isHtml).text
                }
                if (BuildConfig.DIAGNOSTICS) {
                    DiagnosticLog.log(
                        "Offline translation: ${text.length} characters through ${route.size} models in " +
                            "${SystemClock.elapsedRealtime() - startedAt} ms",
                    )
                }
                translated
            }
        }

    /** CLD2's guess of [text]'s language as a short code (e.g. "en"), null when it can't tell. */
    suspend fun detectLanguage(text: String): String? = withContext(Dispatchers.IO) {
        start()
        lock.withLock { detect(text) }
    }

    private fun detect(text: String): String? = runCatching { TranslateKit.detectLanguage(text) }
        .getOrNull()
        ?.takeIf { it.confidence > 0f && it.language != UNKNOWN_LANGUAGE }
        ?.language

    private fun start() {
        check(bergamotSupported()) { "Offline translation needs Android 9 and a 64-bit device" }
        TranslateKit.init(context)
        check(TranslateKit.isInitialized()) { "The offline translation engine could not start" }
    }

    private fun model(files: BergamotModelFiles): TranslationModel = loaded.getOrPut(files.source to files.target) {
        val startedAt = if (BuildConfig.DIAGNOSTICS) SystemClock.elapsedRealtime() else 0L
        TranslateKit.loadModel(
            ModelSpec(
                sourceLang = files.source,
                targetLang = files.target,
                modelPath = files.model.path,
                vocabPaths = files.vocabs.map { it.path },
                shortlistPath = files.shortlist.path,
            ),
        ).also {
            if (BuildConfig.DIAGNOSTICS) {
                DiagnosticLog.log(
                    "Offline translation: model ${files.source} to ${files.target} loaded in " +
                        "${SystemClock.elapsedRealtime() - startedAt} ms",
                )
            }
        }
    }
}

/** What CLD2 answers when it can't tell. */
private const val UNKNOWN_LANGUAGE = "un"

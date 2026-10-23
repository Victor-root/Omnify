package com.looker.droidify.translation.bergamot

import android.app.ActivityManager
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
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
 * The engine translates HTML itself, keeping its tags where they belong, so a description or README is
 * only cut at its blocks, a few large parts translated side by side ([workers]), rather than into every
 * run of text between two tags. Language detection runs on the bundled CLD2.
 */
@Singleton
class BergamotTranslator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val models: BergamotModels,
) {
    /** One translation at a time: its workers have the engine to themselves while it runs. */
    private val lock = Mutex()

    /** The models of the last route, one copy per worker, kept loaded: loading one is what a translation
     *  would otherwise pay for every time. */
    private val loaded = mutableMapOf<Pair<String, String>, MutableList<TranslationModel>>()

    /**
     * How many parts of a page are translated side by side, each on a model of its own. The engine runs
     * on one core per call, and a SHIELD TV took 8.6 s for a 4,000-character description that way. Kept
     * to the cores there are, at most four, and to two below 4 GB of memory, each copy needing room of
     * its own to work in; low-memory devices stay at one.
     */
    private val workers: Int by lazy {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_WORKERS)
        when {
            activityManager.isLowRamDevice -> 1
            memory.totalMem < FOUR_GIGABYTES -> cores.coerceAtMost(2)
            else -> cores
        }.also { workers ->
            if (BuildConfig.DIAGNOSTICS) {
                DiagnosticLog.log(
                    "Offline translation: $workers workers (${Runtime.getRuntime().availableProcessors()} cores, " +
                        "${memory.totalMem / (1024 * 1024)} MB, low RAM ${activityManager.isLowRamDevice})",
                )
            }
        }
    }

    suspend fun translate(text: String, targetLanguage: String, sourceLanguage: String?, isHtml: Boolean): String =
        withContext(Dispatchers.IO) {
            start()
            val source = sourceLanguage ?: lock.withLock { detect(text) } ?: ENGLISH
            val route = models.route(source, targetLanguage)
            lock.withLock {
                val startedAt = if (BuildConfig.DIAGNOSTICS) SystemClock.elapsedRealtime() else 0L
                val wanted = route.map { it.source to it.target }.toSet()
                (loaded.keys - wanted).forEach { pair -> loaded.remove(pair)?.forEach { it.close() } }
                // Only HTML is cut, each part whole HTML of its own (see splitForWorkers).
                val parts = if (isHtml) {
                    splitForWorkers(text, workers)
                } else {
                    listOf(HtmlPiece(emptyList(), text, emptyList()))
                }
                val hops = route.map { files -> copiesOf(files, parts.size) }
                val unwrapFailures = AtomicInteger()
                val translated = coroutineScope {
                    parts.mapIndexed { worker, part ->
                        async {
                            if (part.body.isBlank()) return@async part.body
                            val result = hops.fold(part.html) { current, copies ->
                                copies[worker].translate(current, isHtml).text
                            }
                            // The tags around the part kept rather than lost, should the engine have moved them.
                            part.unwrap(result) ?: result.also { unwrapFailures.incrementAndGet() }
                        }
                    }.awaitAll()
                }.joinToString("")
                if (BuildConfig.DIAGNOSTICS) {
                    DiagnosticLog.log(
                        "Offline translation: ${text.length} characters in ${parts.size} parts through " +
                            "${route.size} models in ${SystemClock.elapsedRealtime() - startedAt} ms, " +
                            "${unwrapFailures.get()} parts left wrapped",
                    )
                }
                translated
            }
        }

    /** Downloads the English to [targetLanguage] model ahead of the first translation. */
    suspend fun prepare(targetLanguage: String) {
        withContext(Dispatchers.IO) {
            start()
            models.route(ENGLISH, targetLanguage)
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

    /** [count] loaded copies of the pair's model, one per worker, loading the ones still missing. */
    private fun copiesOf(files: BergamotModelFiles, count: Int): List<TranslationModel> {
        val copies = loaded.getOrPut(files.source to files.target) { mutableListOf() }
        while (copies.size < count) {
            val startedAt = if (BuildConfig.DIAGNOSTICS) SystemClock.elapsedRealtime() else 0L
            copies += TranslateKit.loadModel(
                ModelSpec(
                    sourceLang = files.source,
                    targetLang = files.target,
                    modelPath = files.model.path,
                    vocabPaths = files.vocabs.map { it.path },
                    shortlistPath = files.shortlist.path,
                ),
            )
            if (BuildConfig.DIAGNOSTICS) {
                DiagnosticLog.log(
                    "Offline translation: model ${files.source} to ${files.target} copy ${copies.size} loaded in " +
                        "${SystemClock.elapsedRealtime() - startedAt} ms",
                )
            }
        }
        return copies
    }
}

/** What CLD2 answers when it can't tell. */
private const val UNKNOWN_LANGUAGE = "un"

private const val MAX_WORKERS = 4
private const val FOUR_GIGABYTES = 4L * 1024 * 1024 * 1024

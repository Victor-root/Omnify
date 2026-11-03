package com.looker.droidify.translation.bergamot

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.duckduckgo.translatekit.TranslateKit
import com.looker.droidify.BuildConfig
import com.looker.droidify.di.ApplicationScope
import com.looker.droidify.utility.diagnostics.DiagnosticLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
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
 * run of text between two tags. Each part goes to an engine in a process of its own
 * ([BergamotWorkerService]): translate-kit's engine is not thread-safe, and parts translated side by side
 * in this process crashed the app now and then. Language detection runs here, on the bundled CLD2.
 */
@Singleton
class BergamotTranslator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val models: BergamotModels,
    @param:ApplicationScope scope: CoroutineScope,
) {
    /** One translation at a time, and detection apart from it. */
    private val lock = Mutex()

    private val pool = BergamotWorkers(context, scope)

    /** Where parts travel to and from the workers, too large to go in a message. Emptied when first used,
     *  of whatever a translation cut short by the app being killed left behind. */
    private val exchange: File by lazy {
        File(context.cacheDir, "bergamot").apply {
            listFiles()?.forEach { it.delete() }
            mkdirs()
        }
    }

    /**
     * How many parts of a page are translated side by side, each by a worker of its own. The engine runs
     * on one core per call, and a SHIELD TV took 8.6 s for a 4,000-character description that way. Kept
     * to the cores there are, at most four (one per worker), and to two below 4 GB of memory, each worker
     * needing room of its own; low-memory devices stay at one.
     */
    private val workers: Int by lazy {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, BERGAMOT_WORKERS.size)
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
            val hops = models.route(source, targetLanguage).map { it.toHop() }
            lock.withLock {
                pool.hold()
                try {
                    translateInParts(text, hops, isHtml)
                } finally {
                    pool.releaseLater()
                }
            }
        }

    private suspend fun translateInParts(text: String, hops: List<WorkerHop>, isHtml: Boolean): String {
        val startedAt = if (BuildConfig.DIAGNOSTICS) SystemClock.elapsedRealtime() else 0L
        // Only HTML is cut, each part whole HTML of its own (see splitForWorkers).
        val parts = if (isHtml) {
            splitForWorkers(text, workers)
        } else {
            listOf(HtmlPiece(emptyList(), text, emptyList()))
        }
        val unwrapFailures = AtomicInteger()
        val translated = coroutineScope {
            parts.mapIndexed { worker, part ->
                async {
                    if (part.body.isBlank()) return@async part.body
                    val result = translateOnWorker(worker, part.html, hops, isHtml)
                    // The tags around the part kept rather than lost, should the engine have moved them.
                    part.unwrap(result) ?: result.also { unwrapFailures.incrementAndGet() }
                }
            }.awaitAll()
        }.joinToString("")
        if (BuildConfig.DIAGNOSTICS) {
            DiagnosticLog.log(
                "Offline translation: ${text.length} characters in ${parts.size} parts through " +
                    "${hops.size} models in ${SystemClock.elapsedRealtime() - startedAt} ms, " +
                    "${unwrapFailures.get()} parts left wrapped",
            )
        }
        return translated
    }

    private suspend fun translateOnWorker(worker: Int, html: String, hops: List<WorkerHop>, isHtml: Boolean): String {
        val input = File.createTempFile("part", ".in", exchange)
        val output = File(exchange, input.name + ".out")
        try {
            input.writeText(html)
            val done = pool.translate(worker, WorkerRequest(hops, input.path, output.path, isHtml))
            check(done) { "The offline translation engine failed on part ${worker + 1}" }
            return output.readText()
        } finally {
            input.delete()
            output.delete()
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
}

/** What CLD2 answers when it can't tell. */
private const val UNKNOWN_LANGUAGE = "un"

private const val FOUR_GIGABYTES = 4L * 1024 * 1024 * 1024

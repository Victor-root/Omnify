package com.looker.droidify.translation.bergamot

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.duckduckgo.translatekit.ModelSpec
import com.duckduckgo.translatekit.TranslateKit
import com.duckduckgo.translatekit.TranslationModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Asks a worker to translate: the request in [KEY_REQUEST], the answer sent to [Message.replyTo]. */
internal const val MSG_TRANSLATE = 1

/** A worker's answer: [Message.arg1] is the request's number, [Message.arg2] one of the RESULT_ values. */
internal const val MSG_TRANSLATED = 2

internal const val RESULT_OK = 0
internal const val RESULT_FAILED = 1

internal const val KEY_REQUEST = "request"

/** The suffix of every worker's process name, ":translate0" and on (see the manifest). */
private const val PROCESS_SUFFIX = ":translate"

/**
 * One language pair's model as a worker loads it, by the paths [BergamotModels] downloaded it to:
 * a worker only ever reads files the app already holds.
 */
@Serializable
internal class WorkerHop(
    val source: String,
    val target: String,
    val model: String,
    val vocabs: List<String>,
    val shortlist: String,
)

/**
 * One part of a page to translate through [hops] in turn, read from [input] and written to [output].
 * Through files rather than in the message, since a README can weigh megabytes and a message to another
 * process cannot.
 */
@Serializable
internal class WorkerRequest(
    val hops: List<WorkerHop>,
    val input: String,
    val output: String,
    val isHtml: Boolean,
)

internal val WORKER_JSON = Json { ignoreUnknownKeys = true }

internal fun BergamotModelFiles.toHop() = WorkerHop(
    source = source,
    target = target,
    model = model.path,
    vocabs = vocabs.map { it.path },
    shortlist = shortlist.path,
)

/** Whether [processName] is one of the translation workers', where the app itself has nothing to do. */
fun isBergamotWorkerProcess(processName: String): Boolean = PROCESS_SUFFIX in processName

/**
 * One translation engine in a process of its own, translating one part of a page at a time for
 * [BergamotTranslator].
 *
 * translate-kit's engine is not thread-safe: several models translating side by side in one process
 * crashed the app now and then, inside the engine. A process apiece shares nothing at all, so parts of
 * a page are translated at once again, and should an engine still crash, only its own process goes,
 * the app carrying on and the part being translated again.
 *
 * Each worker is its own class only because a process is given per class in the manifest.
 */
abstract class BergamotWorkerService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var messenger: Messenger

    /** The models of the last route, kept loaded between parts, as the app kept them before. */
    private val loaded = mutableMapOf<Pair<String, String>, TranslationModel>()

    override fun onCreate() {
        super.onCreate()
        // Translating blocks for as long as it takes, so off the main thread, one request at a time.
        thread = HandlerThread("bergamot-worker").apply { start() }
        handler = Handler(thread.looper) { message ->
            if (message.what == MSG_TRANSLATE) handle(message)
            true
        }
        messenger = Messenger(handler)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        // On the worker's own thread, after the part it may be translating: the models are its alone.
        handler.post {
            loaded.values.forEach { it.close() }
            loaded.clear()
        }
        thread.quitSafely()
        super.onDestroy()
    }

    private fun handle(message: Message) {
        val result = runCatching {
            val request = WORKER_JSON.decodeFromString(
                WorkerRequest.serializer(),
                checkNotNull(message.data.getString(KEY_REQUEST)),
            )
            translate(request)
        }
        val status = if (result.isSuccess) RESULT_OK else RESULT_FAILED
        val reply = Message.obtain(null, MSG_TRANSLATED, message.arg1, status)
        try {
            message.replyTo?.send(reply)
        } catch (_: RemoteException) {
            // The app is gone, and with it whoever was waiting for this part.
        }
    }

    private fun translate(request: WorkerRequest) {
        TranslateKit.init(this)
        check(TranslateKit.isInitialized()) { "The offline translation engine could not start" }
        val wanted = request.hops.map { it.source to it.target }.toSet()
        (loaded.keys - wanted).forEach { pair -> loaded.remove(pair)?.close() }
        val translated = request.hops.fold(File(request.input).readText()) { current, hop ->
            modelFor(hop).translate(current, request.isHtml).text
        }
        File(request.output).writeText(translated)
    }

    private fun modelFor(hop: WorkerHop): TranslationModel = loaded.getOrPut(hop.source to hop.target) {
        TranslateKit.loadModel(
            ModelSpec(
                sourceLang = hop.source,
                targetLang = hop.target,
                modelPath = hop.model,
                vocabPaths = hop.vocabs,
                shortlistPath = hop.shortlist,
            ),
        )
    }
}

class BergamotWorker0 : BergamotWorkerService()
class BergamotWorker1 : BergamotWorkerService()
class BergamotWorker2 : BergamotWorkerService()
class BergamotWorker3 : BergamotWorkerService()

/** The workers, as many as [BergamotTranslator] ever runs side by side. */
internal val BERGAMOT_WORKERS = listOf(
    BergamotWorker0::class.java,
    BergamotWorker1::class.java,
    BergamotWorker2::class.java,
    BergamotWorker3::class.java,
)

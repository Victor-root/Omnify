package com.looker.droidify.translation.bergamot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** How long a worker gets to start and answer the binding. */
private const val CONNECT_TIMEOUT_MS = 15_000L

/** How long the workers stay up after the last translation, ready for the next page without reloading
 *  their models, before they are let go and Android may reclaim their processes. */
private const val IDLE_RELEASE_MS = 120_000L

/** Tries for one part: a worker whose process died is started again for one more. */
private const val ATTEMPTS = 2

/** The worker translating a part went away before answering: its process died. */
private class WorkerGone : Exception()

/**
 * The app's side of [BergamotWorkerService]: starts the workers when a page is translated, hands each
 * its part, and lets them go once a while has passed without any, so their processes and models only
 * take memory while translations happen.
 */
internal class BergamotWorkers(private val context: Context, private val scope: CoroutineScope) {

    private class Pending(val worker: Int, val answer: CompletableDeferred<Boolean>)

    private val pending = ConcurrentHashMap<Int, Pending>()
    private val nextId = AtomicInteger()
    private val connections = BERGAMOT_WORKERS.indices.map { Connection(it) }
    private var release: Job? = null

    private val replies = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            if (message.what == MSG_TRANSLATED) {
                pending.remove(message.arg1)?.answer?.complete(message.arg2 == RESULT_OK)
            }
            true
        },
    )

    /** Keeps the workers up: a translation is under way. */
    fun hold() {
        release?.cancel()
        release = null
    }

    /** Lets the workers go after [IDLE_RELEASE_MS] unless [hold] comes first. */
    fun releaseLater() {
        release?.cancel()
        release = scope.launch {
            delay(IDLE_RELEASE_MS)
            connections.forEach { it.release() }
        }
    }

    /** Has worker [worker] carry out [request], true once its output is written. */
    suspend fun translate(worker: Int, request: WorkerRequest): Boolean {
        repeat(ATTEMPTS) {
            val messenger = connections[worker].messenger()
            val id = nextId.incrementAndGet()
            val answer = CompletableDeferred<Boolean>()
            pending[id] = Pending(worker, answer)
            val message = Message.obtain(null, MSG_TRANSLATE, id, 0).apply {
                data.putString(KEY_REQUEST, WORKER_JSON.encodeToString(WorkerRequest.serializer(), request))
                replyTo = replies
            }
            try {
                messenger.send(message)
                return answer.await()
            } catch (_: WorkerGone) {
                // Its process died: once more, on the worker Android starts again.
            } catch (_: RemoteException) {
                pending.remove(id)
            }
        }
        return false
    }

    /** Fails every part [worker] still had, its process having gone. */
    private fun abandon(worker: Int) {
        pending.entries.removeAll { (_, part) ->
            (part.worker == worker).also { gone -> if (gone) part.answer.completeExceptionally(WorkerGone()) }
        }
    }

    private inner class Connection(private val worker: Int) : ServiceConnection {

        private var bound = false
        private var ready = CompletableDeferred<Messenger>()

        suspend fun messenger(): Messenger {
            val connected = synchronized(this) {
                if (!bound) {
                    bound = context.bindService(
                        Intent(context, BERGAMOT_WORKERS[worker]),
                        this,
                        Context.BIND_AUTO_CREATE,
                    )
                    check(bound) { "The offline translation worker could not be started" }
                }
                ready
            }
            // Not withTimeout: its cancellation would read as the translation being cancelled.
            return withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() }
                ?: error("The offline translation worker did not start")
        }

        fun release() {
            synchronized(this) {
                if (bound) context.unbindService(this)
                bound = false
                ready = CompletableDeferred()
            }
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(this) {
                if (service == null) return
                if (ready.isCompleted) ready = CompletableDeferred()
                ready.complete(Messenger(service))
            }
        }

        /** Its process died. Android starts it again while it stays bound. */
        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(this) {
                // Whoever already waits for it keeps waiting, on the worker coming back.
                if (ready.isCompleted) ready = CompletableDeferred()
            }
            abandon(worker)
        }

        /** The binding itself is lost: the next part binds afresh. */
        override fun onBindingDied(name: ComponentName?) {
            release()
            abandon(worker)
        }
    }
}

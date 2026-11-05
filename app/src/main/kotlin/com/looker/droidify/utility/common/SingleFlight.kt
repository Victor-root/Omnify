package com.looker.droidify.utility.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Runs one call per key at a time, everyone asking for that key meanwhile getting its answer.
 *
 * The call belongs to [scope] rather than to whoever asked first, so one of them being cancelled, a
 * screen being left, never fails the others.
 */
class SingleFlight<K : Any, V>(private val scope: CoroutineScope) {

    private val running = HashMap<K, Deferred<V>>()

    suspend fun run(key: K, call: suspend () -> V): V {
        val shared = synchronized(running) {
            running.getOrPut(key) {
                scope.async(start = CoroutineStart.LAZY) { call() }.also { deferred ->
                    deferred.invokeOnCompletion { synchronized(running) { running.remove(key) } }
                }
            }
        }
        return shared.await()
    }
}

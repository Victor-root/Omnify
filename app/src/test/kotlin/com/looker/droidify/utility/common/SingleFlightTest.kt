package com.looker.droidify.utility.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class SingleFlightTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val flight = SingleFlight<String, Int>(scope)
    private val calls = AtomicInteger()

    @Test
    fun `callers of one key share one call`() = runTest {
        val release = CompletableDeferred<Unit>()
        val answers = List(3) {
            async(Dispatchers.Unconfined) {
                flight.run("key") {
                    calls.incrementAndGet()
                    release.await()
                    7
                }
            }
        }
        release.complete(Unit)
        assertEquals(listOf(7, 7, 7), answers.awaitAll())
        assertEquals(1, calls.get())
    }

    @Test
    fun `a finished call is not kept`() = runTest {
        repeat(2) { flight.run("key") { calls.incrementAndGet() } }
        assertEquals(2, calls.get())
    }

    @Test
    fun `one caller leaving does not fail the other`() = runTest {
        val release = CompletableDeferred<Unit>()
        val call: suspend () -> Int = {
            release.await()
            7
        }
        val leaving = launch(Dispatchers.Unconfined) { flight.run("key", call) }
        val staying = async(Dispatchers.Unconfined) { flight.run("key", call) }
        yield()
        leaving.cancelAndJoin()
        release.complete(Unit)
        assertEquals(7, staying.await())
    }
}

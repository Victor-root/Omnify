package com.looker.droidify.transfer

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [exchange] on this socket and closes it from outside once [deadlineMs] has passed, or once
 * [proofDeadlineMs] has passed without [exchange] calling its argument to say the far end proved
 * itself.
 *
 * A read timeout only ever measures silence, so a peer sending a byte just under it resets it forever.
 * These deadlines run from the start and nothing resets them. Closing is the only thing that breaks a
 * blocking read, so the socket is closed the same way when the caller is cancelled. Called from the IO
 * dispatcher, whose other threads run the watchdog while [exchange] blocks this one.
 */
internal suspend fun <T> Socket.withDeadlines(
    deadlineMs: Long,
    proofDeadlineMs: Long = deadlineMs,
    exchange: (proved: () -> Unit) -> T,
): T = coroutineScope {
    val proof = AtomicBoolean(false)
    val done = AtomicBoolean(false)
    val watchdog = launch {
        try {
            delay(proofDeadlineMs)
            if (!proof.get()) runCatching { close() }
            delay(deadlineMs - proofDeadlineMs)
        } finally {
            // Also reached when the caller is cancelled, which a blocking read would not notice.
            if (!done.get()) runCatching { close() }
        }
    }
    try {
        exchange { proof.set(true) }
    } finally {
        done.set(true)
        watchdog.cancel()
    }
}

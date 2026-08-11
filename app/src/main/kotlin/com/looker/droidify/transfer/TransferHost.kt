package com.looker.droidify.transfer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.coroutineContext

/** How long one read waits before the peer is dropped. Generous for a local network, short enough
 *  that a peer which connected and then went quiet is not sat on for long. */
private const val PEER_READ_TIMEOUT_MS = 15_000

/**
 * How long one connected peer gets in total.
 *
 * Separate from [PEER_READ_TIMEOUT_MS] and the reason that one is not enough on its own: a read
 * timeout only ever measures silence, so a peer sending a single byte every fourteen seconds resets
 * it forever and holds the session open for as long as it likes. This is measured from the moment the
 * connection was accepted and is not reset by anything, so the wait the user is sitting in front of
 * always comes back.
 */
private const val PEER_DEADLINE_MS = 30_000L

/**
 * How long a code stays valid before the session closes itself and the screen offers a new one.
 *
 * The code is the one thing a determined attacker can still work on offline (see PAIRING_CODE_LENGTH),
 * and the work takes tens of minutes, so what makes that search pointless is the code meaning nothing
 * by the time it finishes. Long enough to walk to the other device, short enough that a pairing screen
 * left up on a television overnight is not an open port with a live code on it.
 */
private const val SESSION_LIFETIME_MS = 5 * 60 * 1000L

/** A pause before accepting again after a connection failed to even establish, so that whatever
 *  caused it cannot be repeated fast enough to spin this loop at the device's expense. */
private const val ACCEPT_RETRY_DELAY_MS = 200L

/**
 * How many failed attempts end the session outright.
 *
 * This is the other half of what makes a typed code safe, alongside the cost of deriving a key from
 * one. Stretching puts an offline search out of reach; this puts an online one out of reach too, by
 * making the number of guesses a device on the network gets three rather than unlimited. A person
 * mistyping twice still gets there; anything working through the space does not.
 */
private const val MAX_FAILED_ATTEMPTS = 3

/** How one wait ended. */
sealed interface TransferHostResult {
    /** A backup arrived and decrypted. Still just bytes: reading it is the backup layer's job. */
    class Received(val backup: ByteArray) : TransferHostResult

    /** Too many wrong codes were tried against this session, so it closed itself (see
     *  [MAX_FAILED_ATTEMPTS]). */
    data object Abandoned : TransferHostResult

    /** Nobody came within [SESSION_LIFETIME_MS], so the code stopped being valid. */
    data object Expired : TransferHostResult
}

/**
 * The receiving side: it shows a code, opens a port on the local network, repeats an anonymous
 * announcement so the sending device can find it, and accepts one backup.
 *
 * Receiving is always the side that shows the code, and sending is always the side that types it.
 * That one rule is what keeps the feature explainable: each device is told what to do by the single
 * choice the user already made, and nothing further is asked on either screen. It also falls the
 * right way round for a television, which is the device you would least want to type on and the one
 * most likely to be receiving a phone's settings in the first place.
 */
class TransferHost(private val ioDispatcher: CoroutineDispatcher) {

    private var serverSocket: ServerSocket? = null

    /** Set by the lifetime watchdog before it closes the socket, so that the failure the blocked
     *  accept then throws can be told apart from the user closing the screen. Read from the thread
     *  the accept is blocked on, written from another. */
    @Volatile
    private var expired = false

    /** The code the user reads off this screen, bare. Generated once per instance, so leaving the
     *  screen and coming back is a genuinely new session rather than the same code again. */
    val pairingCode: String = newPairingCode()

    /** This session's half of the key exchange, thrown away with the session. */
    private val keyPair = SessionKeyPair.generate()

    /**
     * Opens the port, or returns false when this device is not on a local network right now (see
     * [localTransferAddress]).
     *
     * Bound to that one address rather than to every interface: the address announced is the only one
     * anything should be able to reach this on, and a device commonly holds others at the same time
     * (a VPN tunnel above all) that have no business carrying this.
     */
    fun bind(): Boolean {
        val address = localTransferAddress() ?: return false
        // A small backlog rather than one: connections are accepted in a tight loop, so the queue
        // drains immediately, and leaving room for a few means a stray connection arriving at the
        // wrong moment cannot make the device the user is actually holding bounce off a full queue.
        val socket = runCatching { ServerSocket(0, 4, address) }.getOrNull() ?: return false
        serverSocket = socket
        return true
    }

    /**
     * Announces this session and takes the backup from the first peer that proves it knows the code.
     *
     * Connections keep being served until one genuinely succeeds, rather than the first one deciding
     * the outcome: an open port on a local network attracts things that are not this app (a scanner,
     * a browser someone pointed at it), and none of that should end the wait the user is sitting in
     * front of. What does end it is [MAX_FAILED_ATTEMPTS] connections that spoke this protocol and
     * got the code wrong, which is the shape of something guessing rather than of a stray packet, or
     * [SESSION_LIFETIME_MS] passing with nobody coming at all.
     */
    suspend fun awaitTransfer(): TransferHostResult = coroutineScope {
        val socket = serverSocket ?: error("bind() first")
        // On the IO dispatcher rather than the caller's: generating the key pair is not free, and
        // deriving a session key once a peer connects is deliberately slow (see PBKDF2_ITERATIONS).
        val announcer = launch(ioDispatcher) {
            announceTransfer(TransferAnnouncement(socket.localPort, keyPair.publicKeyBytes), ioDispatcher)
        }
        // Closing the socket is the only thing that wakes a blocked accept, so an expiry has to be
        // spent rather than merely noticed.
        val lifetime = launch {
            delay(SESSION_LIFETIME_MS)
            expired = true
            close()
        }
        try {
            withContext(ioDispatcher) { accept(socket) }
        } catch (e: IOException) {
            if (expired) TransferHostResult.Expired else throw e
        } finally {
            lifetime.cancel()
            announcer.cancel()
        }
    }

    /** Closes the port. Safe to call more than once, and the way a caller cancels a wait: closing the
     *  socket is what breaks a blocking accept out of its wait. */
    fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun accept(socket: ServerSocket): TransferHostResult {
        var failedAttempts = 0
        while (true) {
            coroutineContext.ensureActive()
            val peer = try {
                socket.accept()
            } catch (e: IOException) {
                // A closed socket is how [close] ends this wait, and it never recovers, so the
                // failure is passed on rather than retried. Anything else is one connection going
                // wrong, which the next one is unaffected by.
                if (socket.isClosed) throw e
                delay(ACCEPT_RETRY_DELAY_MS)
                continue
            }
            val outcome = try {
                peer.use { serve(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // One peer failing is the ordinary case here, not an error worth surfacing: see the
                // loop's own doc comment above.
                PeerOutcome.Ignored
            }
            when (outcome) {
                is PeerOutcome.Completed -> return outcome.result
                PeerOutcome.WrongCode -> {
                    failedAttempts++
                    if (failedAttempts >= MAX_FAILED_ATTEMPTS) return TransferHostResult.Abandoned
                }

                PeerOutcome.Ignored -> Unit
            }
        }
    }

    /** How one connection ended, kept apart from [TransferHostResult] so that only a wrong code
     *  counts against the session and a stray connection never does. */
    private sealed interface PeerOutcome {
        class Completed(val result: TransferHostResult) : PeerOutcome
        data object WrongCode : PeerOutcome
        data object Ignored : PeerOutcome
    }

    /** [exchange] under the absolute deadline described at [PEER_DEADLINE_MS]. The socket is closed
     *  from outside rather than the exchange being cancelled, because the reads it is sitting in are
     *  blocking ones that nothing else interrupts. */
    private suspend fun serve(peer: Socket): PeerOutcome = coroutineScope {
        val deadline = launch {
            delay(PEER_DEADLINE_MS)
            runCatching { peer.close() }
        }
        try {
            exchange(peer)
        } finally {
            deadline.cancel()
        }
    }

    private fun exchange(peer: Socket): PeerOutcome {
        // The first thing checked about a connection, before a single byte of it is read: a peer that
        // is not on this device's own network has no business being served, whatever it goes on to
        // say. Reaching anything further away would mean this backup being routed off the local
        // network, which is the one thing this feature must not do.
        if (!isOnSameLocalNetwork(peer.inetAddress)) return PeerOutcome.Ignored
        peer.soTimeout = PEER_READ_TIMEOUT_MS
        val input = peer.getInputStream().buffered()
        val output = peer.getOutputStream().buffered()
        if (!input.readMagic()) return PeerOutcome.Ignored
        // The peer's half of the exchange, then ours: only the two of us can arrive at the secret
        // behind them, so nothing recorded off the network lets a third party test a guessed code.
        val peerPublicKey = input.readBlock(MAX_PUBLIC_KEY_BYTES) ?: return PeerOutcome.Ignored
        val sharedSecret = keyPair.agree(peerPublicKey) ?: return PeerOutcome.Ignored
        val transcript = transferTranscript(peerPublicKey, keyPair.publicKeyBytes)
        val key = deriveSessionKey(pairingCode, sharedSecret)
        // Nothing has been read beyond a public key at this point, and nothing larger will be read
        // until the peer has answered for the code.
        val offered = input.readBlock(CONFIRMATION_SIZE) ?: return PeerOutcome.Ignored
        if (!confirms(senderConfirmation(key, transcript), offered)) {
            // Reached only by something that spoke this protocol without the right code. It is told
            // so plainly: there is nothing to protect by staying silent, since whoever it is already
            // knows they failed, and the user watching a screen that says "waiting" deserves an app
            // that answers rather than one that hangs up without a word.
            runCatching { output.write(byteArrayOf(STATUS_REJECTED)); output.flush() }
            return PeerOutcome.WrongCode
        }
        // Answered only now that the peer has proved itself, and answered with a value only this
        // session's key produces: it is what the sending device checks before parting with anything,
        // so that a device which simply replied "go ahead" is never taken at its word.
        output.write(byteArrayOf(STATUS_OK))
        output.writeBlock(receiverConfirmation(key, transcript))
        val block = input.readBlock() ?: return PeerOutcome.Ignored
        // Cannot fail on a peer that got this far, since the key is already proved on both sides.
        // Anything landing here is a connection that broke rather than a wrong code, so it does not
        // count against the session.
        val plaintext = decryptTransfer(key, transcript, block) ?: return PeerOutcome.Ignored
        output.writeBlock(receivedConfirmation(key, transcript))
        return PeerOutcome.Completed(TransferHostResult.Received(plaintext))
    }
}

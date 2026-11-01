package com.looker.droidify.transfer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/*
 * Quick share: external sources sent between two devices that were paired once (see TrustedDevices),
 * with no code to read or type.
 *
 * What stands in for the code is the secret the pairing left on both devices, and the exchange is
 * built so that it gives nothing to anyone who does not hold it:
 *
 * 1. The sending device opens with a random nonce and a tag over it, keyed by the secret it shares
 *    with the device it means to reach, and naming both. The receiving device finds which of the
 *    devices it remembers made the tag, or turns the connection away. No identifier ever crosses the
 *    network in the clear, so listening reveals neither who is paired with whom nor how many.
 * 2. The receiving device answers with a nonce of its own, so that nothing recorded from an earlier
 *    exchange can be played back into this one.
 * 3. Both agree on a fresh Diffie-Hellman secret, and the session key is taken from the pairing
 *    secret, that agreement and every value exchanged so far: a key nobody else can compute, and that
 *    is gone with the session even if the pairing secret were ever exposed later.
 * 4. Each side proves it holds that key before anything is sent, and the payload only travels once
 *    both have, encrypted and authenticated under it.
 *
 * What arrives is only ever a list of external sources (see QuickSharePayload), and the receiving
 * device still asks its user before adding any of it.
 */

private const val LABEL_HELLO = "omnify-quick-hello"
private const val LABEL_KEY = "omnify-quick-key"

/** Bytes in each side's nonce. */
private const val NONCE_BYTES = 16

/** Ceiling on a quick share. A handful of sources is a few kilobytes; this leaves room for dozens and
 *  stops anything larger before it is read into memory. */
internal const val MAX_QUICK_SHARE_BYTES = 64 * 1024

private const val CONNECT_TIMEOUT_MS = 5_000
private const val READ_TIMEOUT_MS = 10_000

/** How long one connected peer gets in total, however it trickles its bytes (see withDeadlines). */
private const val PEER_DEADLINE_MS = 20_000L

/** How long one attempt to send gets in total. */
private const val SEND_DEADLINE_MS = 30_000L

/** How long the sending device listens for the device it is sending to. Announcements repeat every
 *  second, so a device that is there is heard well within this. */
private const val LOCATE_TIMEOUT_MS = 4_000L

private const val ACCEPT_RETRY_DELAY_MS = 200L

/** The tag that opens a quick share, naming the device it is meant for and the one it comes from. */
internal fun quickHelloTag(secret: ByteArray, nonce: ByteArray, receiverId: String, senderId: String): ByteArray =
    hmac(
        secret,
        LABEL_HELLO.toByteArray(Charsets.US_ASCII),
        nonce,
        receiverId.toByteArray(Charsets.US_ASCII),
        senderId.toByteArray(Charsets.US_ASCII),
    )

/** Everything both sides exchanged, each value length-prefixed so no two different exchanges can
 *  encode to the same bytes. */
internal fun quickTranscript(
    senderNonce: ByteArray,
    receiverNonce: ByteArray,
    senderPublicKey: ByteArray,
    receiverPublicKey: ByteArray,
    senderId: String,
    receiverId: String,
): ByteArray = listOf(
    senderNonce,
    receiverNonce,
    senderPublicKey,
    receiverPublicKey,
    senderId.toByteArray(Charsets.US_ASCII),
    receiverId.toByteArray(Charsets.US_ASCII),
).fold(ByteArray(0)) { acc, part ->
    acc + byteArrayOf(
        (part.size ushr 24).toByte(),
        (part.size ushr 16).toByte(),
        (part.size ushr 8).toByte(),
        part.size.toByte(),
    ) + part
}

/** The session key: the pairing secret, this exchange's Diffie-Hellman secret, and its transcript. */
internal fun quickSessionKey(secret: ByteArray, sharedSecret: ByteArray, transcript: ByteArray): ByteArray =
    hmac(secret, LABEL_KEY.toByteArray(Charsets.US_ASCII), sharedSecret, transcript)

private fun newNonce(): ByteArray = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }

/**
 * The receiving side: while it runs, this device announces itself on the local network and accepts
 * quick shares from the devices in [trusted], handing each one that arrives to [onReceived], which
 * says whether it was taken. The sender is told either way.
 */
class QuickShareHost(
    private val ioDispatcher: CoroutineDispatcher,
    private val ownId: String,
    private val trusted: () -> List<TrustedDevice>,
    private val onReceived: (from: TrustedDevice, payload: ByteArray) -> Boolean,
) {

    private var serverSocket: ServerSocket? = null

    /** This listener's half of the key exchange, made fresh each time it starts. */
    private val keyPair = SessionKeyPair.generate()

    private val guard = PeerGuard(System::currentTimeMillis)

    /** Opens the port on the local network address, or returns false when there is none (see
     *  TransferHost.bind for why only that one address). */
    fun bind(): Boolean {
        val address = localTransferAddress() ?: return false
        serverSocket = runCatching { ServerSocket(0, 4, address) }.getOrNull() ?: return false
        return true
    }

    /** Announces and serves until [close] or cancellation. */
    suspend fun serve(): Unit = coroutineScope {
        val socket = serverSocket ?: error("bind() first")
        val announcer = launch(ioDispatcher) {
            announceTransfer(
                TransferChannel.QUICK_SHARE,
                TransferAnnouncement(socket.localPort, keyPair.publicKeyBytes),
                ioDispatcher,
            )
        }
        // Cancelling does not interrupt a blocking accept; closing the socket does.
        val closer = launch {
            try {
                awaitCancellation()
            } finally {
                close()
            }
        }
        try {
            withContext(ioDispatcher) { acceptLoop(socket) }
        } catch (_: IOException) {
            // The socket closed: [close] ending the listener.
        } finally {
            announcer.cancel()
            closer.cancel()
        }
    }

    fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    /** Serves several peers at once, within [PeerGuard]'s limits, so that one connection left hanging
     *  never keeps a paired device out. */
    private suspend fun acceptLoop(socket: ServerSocket): Unit = coroutineScope {
        while (true) {
            ensureActive()
            val peer = try {
                socket.accept()
            } catch (e: IOException) {
                if (socket.isClosed) throw e
                delay(ACCEPT_RETRY_DELAY_MS)
                continue
            }
            val address = peer.inetAddress
            if (!guard.admit(address)) {
                runCatching { peer.close() }
                continue
            }
            launch {
                val served = try {
                    peer.use { it.withDeadlines(PEER_DEADLINE_MS) { _ -> exchange(it) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Outcome.BROKEN
                }
                guard.release(address, failed = served == Outcome.BROKEN)
            }
        }
    }

    private enum class Outcome {
        /** The exchange ran to its end, whatever that end was. */
        COMPLETED,

        /** The peer was not paired with this device: an honest sender looking for another one. */
        NOT_FOR_US,

        /** The peer went silent, sent something that is not this protocol, or failed a proof. */
        BROKEN,
    }

    private fun exchange(peer: Socket): Outcome {
        if (!isOnSameLocalNetwork(peer.inetAddress)) return Outcome.BROKEN
        peer.soTimeout = READ_TIMEOUT_MS
        val input = peer.getInputStream().buffered()
        val output = peer.getOutputStream().buffered()
        if (!input.readMagic(TransferChannel.QUICK_SHARE)) return Outcome.BROKEN
        val senderNonce = input.readExactBlock(NONCE_BYTES) ?: return Outcome.BROKEN
        val tag = input.readExactBlock(CONFIRMATION_SIZE) ?: return Outcome.BROKEN
        val senderPublicKey = input.readBlock(MAX_PUBLIC_KEY_BYTES) ?: return Outcome.BROKEN
        // Which remembered device made the tag, and so whose secret this exchange runs under. One
        // HMAC per remembered device, which is bounded (see MAX_TRUSTED_DEVICES), and nothing heavier
        // is spent on a connection before it has matched one.
        val from = trusted().firstOrNull { device ->
            confirms(quickHelloTag(device.secret, senderNonce, ownId, device.id), tag)
        }
        if (from == null) {
            output.reject()
            return Outcome.NOT_FOR_US
        }
        val receiverNonce = newNonce()
        output.write(byteArrayOf(STATUS_OK))
        output.writeBlock(receiverNonce)
        val sharedSecret = keyPair.agree(senderPublicKey) ?: return Outcome.BROKEN
        val transcript = quickTranscript(
            senderNonce = senderNonce,
            receiverNonce = receiverNonce,
            senderPublicKey = senderPublicKey,
            receiverPublicKey = keyPair.publicKeyBytes,
            senderId = from.id,
            receiverId = ownId,
        )
        val key = quickSessionKey(from.secret, sharedSecret, transcript)
        if (!confirms(senderConfirmation(key, transcript), input.readBlock(CONFIRMATION_SIZE))) {
            output.reject()
            return Outcome.BROKEN
        }
        output.write(byteArrayOf(STATUS_OK))
        output.writeBlock(receiverConfirmation(key, transcript))
        val block = input.readBlock(MAX_QUICK_SHARE_BYTES + CONFIRMATION_SIZE) ?: return Outcome.BROKEN
        val payload = decryptTransfer(TransferChannel.QUICK_SHARE, key, transcript, block)
            ?: return Outcome.BROKEN
        // Acknowledged only once it is taken: one already waiting to be answered here, or one that
        // is not a list of sources, is turned away and the sender says so.
        if (!onReceived(from, payload)) {
            output.reject()
            return Outcome.COMPLETED
        }
        output.write(byteArrayOf(STATUS_OK))
        output.writeBlock(receivedConfirmation(key, transcript))
        return Outcome.COMPLETED
    }
}

/**
 * Who may connect to a [QuickShareHost], so that nothing on the network can keep it busy: a few
 * connections at once, fewer from any one address, and an address whose connections keep going nowhere
 * is turned away for a while.
 *
 * Guessing is hopeless against a 256-bit pairing secret, so none of this defends against guessing. A
 * sender reaching a device it is not paired with is honest (it tries every listener on the network
 * until one recognises it) and is not counted.
 */
internal class PeerGuard(private val clock: () -> Long) {

    private val active = HashMap<InetAddress, Int>()
    private val failures = HashMap<InetAddress, Int>()
    private val blockedUntil = HashMap<InetAddress, Long>()

    @Synchronized
    fun admit(address: InetAddress): Boolean {
        val now = clock()
        blockedUntil.values.removeAll { it <= now }
        if (address in blockedUntil) return false
        if (active.values.sum() >= MAX_CONCURRENT_PEERS) return false
        val fromAddress = active[address] ?: 0
        if (fromAddress >= MAX_PEERS_PER_ADDRESS) return false
        active[address] = fromAddress + 1
        return true
    }

    @Synchronized
    fun release(address: InetAddress, failed: Boolean) {
        val left = (active[address] ?: 1) - 1
        if (left > 0) active[address] = left else active.remove(address)
        if (!failed) {
            failures.remove(address)
            return
        }
        val count = (failures[address] ?: 0) + 1
        if (count >= MAX_FAILURES_PER_ADDRESS) {
            failures.remove(address)
            blockedUntil[address] = clock() + COOLDOWN_MS
        } else {
            failures[address] = count
        }
    }

    internal companion object {
        const val MAX_CONCURRENT_PEERS = 4
        const val MAX_PEERS_PER_ADDRESS = 2
        const val MAX_FAILURES_PER_ADDRESS = 5
        const val COOLDOWN_MS = 60_000L
    }
}

/** How one quick share ended. */
enum class QuickShareResult {
    SENT,

    /** The device was not heard on this network: it is off, Omnify is not open on it, or it is on
     *  another network. */
    NOT_FOUND,

    /** The device took the connection and turned the share away: one is already waiting to be
     *  answered there, or what was sent is not a source it can add. */
    REFUSED,

    /** It was found, but no longer recognises this device (it was unpaired there), or the connection
     *  did not survive. */
    FAILED,

    /** Something answered as that device and could not prove it. Nothing was sent to it. */
    IMPOSTOR,
}

/** The sending side: finds [QuickShareHost]s on the local network and hands a payload to the one that
 *  is the device it is meant for. */
class QuickShareClient(private val ioDispatcher: CoroutineDispatcher) {

    suspend fun send(ownId: String, to: TrustedDevice, payload: ByteArray): QuickShareResult =
        withContext(ioDispatcher) {
            val hosts = findTransferHosts(TransferChannel.QUICK_SHARE, ioDispatcher, LOCATE_TIMEOUT_MS)
            var outcome = QuickShareResult.NOT_FOUND
            for (host in hosts) {
                ensureActive()
                when (val result = sendTo(host, ownId, to, payload)) {
                    QuickShareResult.SENT, QuickShareResult.REFUSED -> return@withContext result
                    // Another paired device on the same network turns the tag away, since it was not
                    // made for it, which is not a failure of this share: the next host may be the one.
                    QuickShareResult.NOT_FOUND -> Unit
                    QuickShareResult.FAILED, QuickShareResult.IMPOSTOR -> if (result > outcome) outcome = result
                }
            }
            outcome
        }

    private suspend fun sendTo(
        host: DiscoveredHost,
        ownId: String,
        to: TrustedDevice,
        payload: ByteArray,
    ): QuickShareResult {
        if (!isOnSameLocalNetwork(host.address)) return QuickShareResult.NOT_FOUND
        return try {
            val keyPair = SessionKeyPair.generate()
            val sharedSecret = keyPair.agree(host.publicKey) ?: return QuickShareResult.FAILED
            Socket().use { socket ->
                socket.withDeadlines(SEND_DEADLINE_MS) { _ ->
                    socket.connect(InetSocketAddress(host.address, host.port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = READ_TIMEOUT_MS
                    val output = socket.getOutputStream().buffered()
                    val input = socket.getInputStream().buffered()
                    val senderNonce = newNonce()
                    output.writeMagic(TransferChannel.QUICK_SHARE)
                    output.writeBlock(senderNonce)
                    output.writeBlock(quickHelloTag(to.secret, senderNonce, to.id, ownId))
                    output.writeBlock(keyPair.publicKeyBytes)
                    if (input.read() != STATUS_OK.toInt()) return@withDeadlines QuickShareResult.NOT_FOUND
                    val receiverNonce = input.readExactBlock(NONCE_BYTES)
                        ?: return@withDeadlines QuickShareResult.FAILED
                    val transcript = quickTranscript(
                        senderNonce = senderNonce,
                        receiverNonce = receiverNonce,
                        senderPublicKey = keyPair.publicKeyBytes,
                        receiverPublicKey = host.publicKey,
                        senderId = ownId,
                        receiverId = to.id,
                    )
                    val key = quickSessionKey(to.secret, sharedSecret, transcript)
                    output.writeBlock(senderConfirmation(key, transcript))
                    if (input.read() != STATUS_OK.toInt()) return@withDeadlines QuickShareResult.FAILED
                    if (!confirms(receiverConfirmation(key, transcript), input.readBlock(CONFIRMATION_SIZE))) {
                        return@withDeadlines QuickShareResult.IMPOSTOR
                    }
                    output.writeBlock(encryptTransfer(TransferChannel.QUICK_SHARE, key, transcript, payload))
                    // The refusal is not authenticated, and needs not be: the device already proved
                    // itself, and all a forged one could do is what dropping the connection does.
                    when (input.read()) {
                        STATUS_OK.toInt() -> Unit
                        STATUS_REJECTED.toInt() -> return@withDeadlines QuickShareResult.REFUSED
                        else -> return@withDeadlines QuickShareResult.FAILED
                    }
                    if (confirms(receivedConfirmation(key, transcript), input.readBlock(CONFIRMATION_SIZE))) {
                        QuickShareResult.SENT
                    } else {
                        QuickShareResult.FAILED
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            QuickShareResult.FAILED
        }
    }
}

private fun OutputStream.reject() {
    runCatching { write(byteArrayOf(STATUS_REJECTED)); flush() }
}

/** A block that must be exactly [size] bytes, or null. */
private fun InputStream.readExactBlock(size: Int): ByteArray? = readBlock(size)?.takeIf { it.size == size }

package com.looker.droidify.transfer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** How long the other device gets to answer. Short: both are on the same local network, so anything
 *  slower than this is a device that has already stopped waiting, which does not get better by
 *  waiting longer. */
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 30_000

/**
 * How long the far end gets, from the connection, to prove it holds the code, however it paces its
 * bytes.
 *
 * The read timeout alone only measures silence, so a device answering a byte at a time could keep an
 * attempt open for minutes, and those minutes are exactly what something that put itself in the
 * middle needs: it holds this device's proof from the first message on, and a short code can be
 * searched offline against it given time. Closing the attempt this soon leaves that search no time to
 * finish while the code is still being waited on.
 */
private const val PROOF_DEADLINE_MS = 10_000L

/** How long one attempt gets in all, the payload and the answer included. */
private const val ATTEMPT_DEADLINE_MS = 60_000L

/**
 * Why a transfer did not happen, in the terms the user needs rather than the ones the socket used.
 *
 * Declared least telling first, which is the order [tellsMoreThan] reads when several devices were
 * tried and each failed in its own way.
 */
enum class TransferFailure {
    /** No device on this network is waiting with that code. Either it was mistyped, the other screen
     *  has since been left, or the two devices are not on the same network after all. */
    NOT_FOUND,

    /** The device was found but the connection did not survive. */
    UNREACHABLE,

    /** The device answered and refused: the code was wrong, or the session had already ended. */
    REJECTED,

    /** Something on this network answered as though it were the other device and could not prove it.
     *  Nothing was sent to it. Worth its own message rather than being folded into a lost connection:
     *  this one does not happen by accident. */
    IMPOSTOR,
}

/** Ceiling on what the receiving device may answer with (pairing: its identity, a few dozen bytes). */
private const val MAX_REPLY_BYTES = 4096

/** How one send ended. */
sealed interface TransferSendResult {
    /**
     * The payload arrived. [reply] is what the receiving device answered with, for a conversation
     * that expects one, and [secret] what the two devices can keep to recognise each other later (see
     * [pairingSecret]); a backup transfer uses neither.
     */
    class Sent(val reply: ByteArray?, internal val secret: ByteArray) : TransferSendResult
    class Failed(val reason: TransferFailure) : TransferSendResult
}

/**
 * The sending side: it finds the devices waiting on this network (see [findTransferHosts]), then
 * hands the backup to whichever one the typed code belongs to.
 *
 * Sending is always the side that types the code, receiving always the side that shows one, so
 * nothing here has to ask which way the data should go: the user already said so by choosing to send.
 */
class TransferClient(
    private val ioDispatcher: CoroutineDispatcher,
    private val channel: TransferChannel,
) {

    /** Every device heard waiting on this network on [channel], or none after [timeoutMs]. */
    suspend fun locate(timeoutMs: Long): List<DiscoveredHost> = findTransferHosts(channel, ioDispatcher, timeoutMs)

    /**
     * Hands [backup] to whichever of [hosts] the typed [code] turns out to belong to.
     *
     * Nothing announced on the network says which device that is (see [TransferAnnouncement]), so it
     * is settled by attempting each in turn until one accepts. In practice there is one waiting
     * device and the first attempt is the only one; more than one simply means trying them. Trying
     * several is safe because an attempt gives nothing away: a device that cannot answer for the code
     * is left with an unusable handshake and no data (see [sendTo]).
     *
     * The most telling failure wins when none accepts. Something answering without being able to
     * prove it holds the code says more than a wrong code does, and a wrong code says more than
     * "nothing answered" from some other candidate.
     */
    suspend fun send(
        hosts: List<DiscoveredHost>,
        code: String,
        backup: ByteArray,
        expectReply: Boolean = false,
    ): TransferSendResult = withContext(ioDispatcher) {
        if (hosts.isEmpty()) return@withContext TransferSendResult.Failed(TransferFailure.NOT_FOUND)
        var worst: TransferFailure? = null
        hosts.forEach { host ->
            // Between hosts rather than only at the start: each attempt can take up to the connect
            // and read timeouts above, and the user closing the screen should not be waited out.
            ensureActive()
            when (val result = sendTo(host, code, backup, expectReply)) {
                is TransferSendResult.Sent -> return@withContext result
                is TransferSendResult.Failed -> if (result.reason.tellsMoreThan(worst)) worst = result.reason
            }
        }
        TransferSendResult.Failed(worst ?: TransferFailure.UNREACHABLE)
    }

    /**
     * One attempt against one device, which parts with [backup] only once that device has proved it
     * holds [code].
     *
     * The order here is the whole security of the feature and is worth spelling out. This device
     * sends its public half and a proof that it knows the code; the far end can only answer that
     * proof by knowing the code too, so a device that merely announced itself on the network is
     * turned away here, holding a handshake it cannot use and none of the data. It matters because a
     * backup can carry repository passwords and a GitHub token: handing those to whoever answers and
     * trusting encryption alone to sort it out afterwards would mean a short code being all that
     * stood between an eavesdropper and them, at their leisure.
     */
    private suspend fun sendTo(
        host: DiscoveredHost,
        code: String,
        backup: ByteArray,
        expectReply: Boolean,
    ): TransferSendResult {
        // Checked again here, not just when the announcement was heard: what a backup carries is
        // worth confirming twice that it is only ever opened towards this device's own local network.
        if (!isOnSameLocalNetwork(host.address)) {
            return TransferSendResult.Failed(TransferFailure.NOT_FOUND)
        }
        return try {
            val keyPair = SessionKeyPair.generate()
            val sharedSecret = keyPair.agree(host.publicKey)
                ?: return TransferSendResult.Failed(TransferFailure.UNREACHABLE)
            val transcript = transferTranscript(keyPair.publicKeyBytes, host.publicKey)
            val key = deriveSessionKey(code, sharedSecret)
            Socket().use { socket ->
                socket.withDeadlines(ATTEMPT_DEADLINE_MS, PROOF_DEADLINE_MS) { proved ->
                    socket.connect(InetSocketAddress(host.address, host.port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = READ_TIMEOUT_MS
                    val output = socket.getOutputStream().buffered()
                    val input = socket.getInputStream().buffered()
                    output.writeMagic(channel)
                    output.writeBlock(keyPair.publicKeyBytes)
                    output.writeBlock(senderConfirmation(key, transcript))
                    when (input.read()) {
                        STATUS_OK.toInt() -> Unit
                        STATUS_REJECTED.toInt() ->
                            return@withDeadlines TransferSendResult.Failed(TransferFailure.REJECTED)
                        // Including -1, which is the connection having gone rather than an answer.
                        else -> return@withDeadlines TransferSendResult.Failed(TransferFailure.UNREACHABLE)
                    }
                    // The far end said yes. Whether it may have the data is settled by its answer, not by
                    // it having said yes.
                    val answer = input.readBlock(CONFIRMATION_SIZE)
                    if (!confirms(receiverConfirmation(key, transcript), answer)) {
                        return@withDeadlines TransferSendResult.Failed(TransferFailure.IMPOSTOR)
                    }
                    proved()
                    output.writeBlock(encryptTransfer(channel, key, transcript, backup))
                    // Only now is "sent" true. The acknowledgement is a value only this session's key
                    // produces, so the screen says the data arrived because it did, not because something
                    // on the network was willing to say so.
                    if (!confirms(receivedConfirmation(key, transcript), input.readBlock(CONFIRMATION_SIZE))) {
                        return@withDeadlines TransferSendResult.Failed(TransferFailure.UNREACHABLE)
                    }
                    val reply = if (expectReply) {
                        input.readBlock(MAX_REPLY_BYTES)
                            ?.let { decryptTransfer(channel, replyKey(key), transcript, it) }
                            ?: return@withDeadlines TransferSendResult.Failed(TransferFailure.UNREACHABLE)
                    } else {
                        null
                    }
                    TransferSendResult.Sent(reply, pairingSecret(key, transcript))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            TransferSendResult.Failed(TransferFailure.UNREACHABLE)
        }
    }
}

/** Which of two failures is worth showing the user: the one that says most about what actually
 *  happened, by the order [TransferFailure] declares. */
private fun TransferFailure.tellsMoreThan(other: TransferFailure?): Boolean =
    other == null || ordinal > other.ordinal

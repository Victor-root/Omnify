package com.looker.droidify.transfer

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Marks the start of a transfer stream, so a stray connection (a port scanner, a browser) is turned
 *  away on its first bytes instead of being read as a truncated backup. */
private val PROTOCOL_MAGIC = "OMNIFYT2".toByteArray(Charsets.US_ASCII)

internal const val STATUS_OK: Byte = 0
internal const val STATUS_REJECTED: Byte = 1

/** One HMAC-SHA-256 output. Every proof exchanged during the handshake is this size, so a peer can
 *  never make the other side read more than this looking for one. */
internal const val CONFIRMATION_SIZE = 32

/**
 * What each proof is computed over, on top of the session key and the transcript.
 *
 * Three different labels rather than one value used three times, so that a proof captured in one
 * direction cannot be replayed in another: the sending device's proof of knowing the code is not
 * something the receiving device would ever accept as its own answer, and neither is the
 * acknowledgement at the end.
 */
private const val LABEL_SENDER = "omnify-transfer-sender"
private const val LABEL_RECEIVER = "omnify-transfer-receiver"
private const val LABEL_RECEIVED = "omnify-transfer-received"

/**
 * Ceiling on a single transferred payload. A backup of this app's data is comfortably under a
 * megabyte even with a few hundred tracked sources (see BackupRepository's own limits), so this only
 * ever stops something that was never one, before it is read into memory rather than after.
 */
internal const val MAX_TRANSFER_BYTES = 16 * 1024 * 1024

private const val NONCE_SIZE = 12
private const val TAG_BITS = 128
private const val TRANSFORMATION = "AES/GCM/NoPadding"

/**
 * How many digits the user reads off one screen and types on the other.
 *
 * Eight is short enough to be typed on a numeric keypad without a second thought, and it can be that
 * short only because of how little the protocol ever puts at stake on it. Nothing is broadcast that
 * a guess can be tested against, and no data is handed to anyone before they have proved they hold
 * the code, so a device that merely turns up on the network learns nothing from either.
 *
 * What a device that speaks the protocol can obtain is one proof (see [senderConfirmation]) to search
 * offline: eight digits at [PBKDF2_ITERATIONS] is on the order of a hundred million stretched
 * guesses, tens of minutes of dedicated hardware. That search recovers the code and nothing else,
 * and it recovers it for a code that stops meaning anything the moment the session ends, which is why
 * a session is deliberately short lived (see TransferHost's own lifetime) and why a code is never
 * reused. It is worth naming as the limit it is: this is not a password-authenticated exchange, which
 * would make even that search impossible, and implementing one properly is not something to hand-roll.
 */
private const val PAIRING_CODE_LENGTH = 8

/**
 * Deliberately expensive, so that each guess at the code costs this many HMAC-SHA-256 operations
 * instead of one. Paid once per session on each device, taking well under a second on a phone and
 * around a second on a slow TV, while an attacker pays it for every candidate.
 */
private const val PBKDF2_ITERATIONS = 100_000

/** A fresh code for one pairing, in its bare form: digits only, no separators. */
internal fun newPairingCode(): String {
    val random = SecureRandom()
    return buildString { repeat(PAIRING_CODE_LENGTH) { append(random.nextInt(10)) } }
}

/** A code split into groups for display, which is how a person keeps their place while reading it
 *  off one screen and typing it into another. */
fun formatPairingCode(code: String): String = code.chunked(4).joinToString(" ")

/**
 * What the user typed, reduced to the bare code, or null when it could not be one.
 *
 * Forgiving on purpose, since every rejection here is a person retyping: anything that is not a
 * digit (the separator from [formatPairingCode], spaces a keyboard added) is simply dropped rather
 * than refused.
 */
fun normalisePairingCode(input: String): String? =
    input.filter { it.isDigit() }.takeIf { it.length == PAIRING_CODE_LENGTH }

/**
 * The key both devices encrypt and prove under, from the code and from the Diffie-Hellman secret only
 * they two share.
 *
 * Binding both together is what lets the code be eight digits. The shared secret alone would stop
 * anyone listening, but not someone who put themselves in the middle and did their own exchange with
 * each side; the code alone would stop that, but would have to be long enough to survive being
 * searched offline by anyone who recorded the traffic. Together, listening yields nothing at all,
 * and the short code only ever has to hold against an attacker who was already intercepting live.
 *
 * The exchange is folded in as PBKDF2's salt rather than hashed with the code, so that a candidate
 * key cannot even be computed without having taken part in the exchange.
 */
internal fun deriveSessionKey(code: String, sharedSecret: ByteArray): ByteArray = pbkdf2HmacSha256(
    password = code.toByteArray(Charsets.US_ASCII),
    salt = sharedSecret,
    iterations = PBKDF2_ITERATIONS,
    outputLength = 32,
)

/**
 * PBKDF2 with HMAC-SHA-256, written out rather than taken from `SecretKeyFactory`.
 *
 * The platform only registers `PBKDF2WithHmacSHA256` from API 26, and this app still supports
 * Android 6 (minSdk 23). The alternatives were to fall back to the SHA-1 variant on older devices,
 * which would mean two devices deriving different keys from the same code depending on which Android
 * each runs, or to write out the one loop RFC 2898 defines. `Mac` itself has been there since the
 * beginning, so this is the same primitive the platform would have used, driven by hand.
 */
private fun pbkdf2HmacSha256(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    outputLength: Int,
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(password, "HmacSHA256"))
    val blockSize = mac.macLength
    val output = ByteArray(outputLength)
    var written = 0
    var blockIndex = 1
    while (written < outputLength) {
        // U1 = PRF(password, salt || INT_32_BE(blockIndex))
        mac.update(salt)
        mac.update(
            byteArrayOf(
                (blockIndex ushr 24).toByte(),
                (blockIndex ushr 16).toByte(),
                (blockIndex ushr 8).toByte(),
                blockIndex.toByte(),
            ),
        )
        var u = mac.doFinal()
        val block = u.copyOf()
        // Ui = PRF(password, U(i-1)), all XORed together.
        repeat(iterations - 1) {
            u = mac.doFinal(u)
            for (i in block.indices) block[i] = (block[i].toInt() xor u[i].toInt()).toByte()
        }
        val take = minOf(blockSize, outputLength - written)
        block.copyInto(output, written, 0, take)
        written += take
        blockIndex++
    }
    return output
}

/**
 * The two public halves of one exchange, in a form nothing else could also encode.
 *
 * Every proof and the payload's own authentication are bound to this, so that a proof produced for
 * one pairing is worthless in any other: an attacker who records a handshake cannot replay a single
 * byte of it towards a device whose key pair differs, which is every device and every session, since
 * both pairs exist for one pairing only.
 *
 * Each half carries its own length rather than simply being run together, so that no two different
 * pairs of keys can ever produce the same bytes.
 */
internal fun transferTranscript(senderPublicKey: ByteArray, receiverPublicKey: ByteArray): ByteArray {
    fun ByteArray.lengthPrefix() = byteArrayOf(
        (size ushr 24).toByte(),
        (size ushr 16).toByte(),
        (size ushr 8).toByte(),
        size.toByte(),
    )
    return senderPublicKey.lengthPrefix() + senderPublicKey +
        receiverPublicKey.lengthPrefix() + receiverPublicKey
}

/**
 * The sending device's proof that it holds the code, sent before any data is.
 *
 * This is the order the whole feature rests on. Encrypting the backup and handing it to whichever
 * device answered would not be enough on its own: that device holds its own half of the exchange, so
 * it could compute candidate keys and search a short code offline until the ciphertext opened. Making
 * it answer first means a device that does not hold the code is left with a handshake and nothing to
 * work on, whatever it announced and however long it waits.
 */
internal fun senderConfirmation(key: ByteArray, transcript: ByteArray): ByteArray =
    confirmation(key, LABEL_SENDER, transcript)

/** The receiving device's answer, which the sending device checks before parting with anything. Without
 *  it, a device that simply replied "go ahead" would be handed the backup on its word alone. */
internal fun receiverConfirmation(key: ByteArray, transcript: ByteArray): ByteArray =
    confirmation(key, LABEL_RECEIVER, transcript)

/** The receiving device's acknowledgement that the backup arrived and decrypted, so that "sent" on the
 *  other screen means the data genuinely got there rather than that something answered. */
internal fun receivedConfirmation(key: ByteArray, transcript: ByteArray): ByteArray =
    confirmation(key, LABEL_RECEIVED, transcript)

private fun confirmation(key: ByteArray, label: String, transcript: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    mac.update(label.toByteArray(Charsets.US_ASCII))
    mac.update(transcript)
    return mac.doFinal()
}

/** Whether [received] is the proof that was expected, compared in constant time so that a wrong one
 *  cannot be improved a byte at a time by watching how long the comparison took. */
internal fun confirms(expected: ByteArray, received: ByteArray?): Boolean =
    received != null && MessageDigest.isEqual(expected, received)

/**
 * [payload] encrypted under [key], as one self-contained block of `nonce || ciphertext+tag`.
 *
 * GCM authenticates as well as encrypts, and [transcript] is authenticated alongside the payload
 * without being sent, so the block only opens on a device that took part in this exact exchange.
 */
internal fun encryptTransfer(key: ByteArray, transcript: ByteArray, payload: ByteArray): ByteArray {
    val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
    cipher.updateAAD(PROTOCOL_MAGIC)
    cipher.updateAAD(transcript)
    return nonce + cipher.doFinal(payload)
}

/** The plaintext behind [block], or null when it was not produced by [encryptTransfer] under the same
 *  [key] and [transcript]: a wrong code, a truncated read, and a tampered payload all land here. */
internal fun decryptTransfer(key: ByteArray, transcript: ByteArray, block: ByteArray): ByteArray? = runCatching {
    if (block.size <= NONCE_SIZE) return null
    val cipher = Cipher.getInstance(TRANSFORMATION)
    val nonce = block.copyOfRange(0, NONCE_SIZE)
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
    cipher.updateAAD(PROTOCOL_MAGIC)
    cipher.updateAAD(transcript)
    cipher.doFinal(block, NONCE_SIZE, block.size - NONCE_SIZE)
}.getOrNull()

internal fun OutputStream.writeMagic() = write(PROTOCOL_MAGIC)

/** True when the stream opens with [PROTOCOL_MAGIC]. Consumes exactly those bytes either way. */
internal fun InputStream.readMagic(): Boolean = runCatching {
    val header = ByteArray(PROTOCOL_MAGIC.size)
    DataInputStream(this).readFully(header)
    header.contentEquals(PROTOCOL_MAGIC)
}.getOrDefault(false)

/** Writes [block] with its own length in front, so the reader knows where it ends without relying on
 *  the connection closing. */
internal fun OutputStream.writeBlock(block: ByteArray) {
    write((block.size ushr 24) and 0xFF)
    write((block.size ushr 16) and 0xFF)
    write((block.size ushr 8) and 0xFF)
    write(block.size and 0xFF)
    write(block)
    flush()
}

/**
 * The next length-prefixed block, or null when the declared length is over [limit].
 *
 * The length is checked before a buffer is allocated for it, never after: the number arrives from the
 * network, and honouring a claimed four-gigabyte block for even as long as it takes to allocate is
 * all it would take to put the app down from another device on the network. [limit] is what each
 * caller actually expects rather than one ceiling for everything, so a handshake block cannot be used
 * to make the other side hold megabytes before it has proved anything at all.
 */
internal fun InputStream.readBlock(limit: Int = MAX_TRANSFER_BYTES): ByteArray? {
    val stream = DataInputStream(this)
    val size = runCatching { stream.readInt() }.getOrNull() ?: return null
    if (size <= 0 || size > limit) return null
    val block = ByteArray(size)
    return runCatching { stream.readFully(block); block }.getOrNull()
}

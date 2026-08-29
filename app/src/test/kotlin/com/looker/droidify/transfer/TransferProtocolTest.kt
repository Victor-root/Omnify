package com.looker.droidify.transfer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pairing code, the key exchange, and the encryption behind a device-to-device transfer.
 *
 * Worth testing directly because both ends of this are the same app: a mistake in how a code is read
 * back, or in what the key is derived from, would show up as two devices that simply refuse each
 * other, with nothing to distinguish it from the code having been mistyped. These pin down the parts
 * that have to agree, and the ones the security rests on.
 */
class TransferProtocolTest {

    private val secret = ByteArray(32) { it.toByte() }
    private val transcript = transferTranscript(ByteArray(91) { 1 }, ByteArray(91) { 2 })

    @Test
    fun `a generated code is eight digits`() {
        // Digits only, so the field can open a numeric keypad and the code is quick to read out.
        repeat(50) {
            val code = newPairingCode()
            assertEquals(8, code.length)
            assertTrue(code.all { it.isDigit() })
        }
    }

    @Test
    fun `two codes in a row differ`() {
        assertNotEquals(newPairingCode(), newPairingCode())
    }

    @Test
    fun `a code is displayed in groups and read back through them`() {
        assertEquals("1234 5678", formatPairingCode("12345678"))
        assertEquals("12345678", normalisePairingCode("1234 5678"))
    }

    @Test
    fun `reading a typed code drops whatever a keyboard added`() {
        assertEquals("12345678", normalisePairingCode("1234-5678"))
        assertEquals("12345678", normalisePairingCode("  1234 5678  "))
    }

    @Test
    fun `a code of the wrong length is refused rather than half accepted`() {
        assertNull(normalisePairingCode(""))
        assertNull(normalisePairingCode("1234"))
        assertNull(normalisePairingCode("123456789"))
        assertNull(normalisePairingCode("abcdefgh"))
    }

    @Test
    fun `both devices reach the same secret without it crossing the network`() {
        val receiving = SessionKeyPair.generate()
        val sending = SessionKeyPair.generate()
        val onReceiving = receiving.agree(sending.publicKeyBytes)
        val onSending = sending.agree(receiving.publicKeyBytes)
        assertNotNull(onReceiving)
        assertContentEquals(onReceiving, onSending)
    }

    @Test
    fun `a third key pair reaches a different secret`() {
        // The whole point of the exchange: someone who recorded both public halves still cannot
        // arrive at what the two devices did, so a short code has nothing to be guessed against.
        val receiving = SessionKeyPair.generate()
        val sending = SessionKeyPair.generate()
        val eavesdropper = SessionKeyPair.generate()
        assertNotEquals(
            receiving.agree(sending.publicKeyBytes)?.toList(),
            eavesdropper.agree(sending.publicKeyBytes)?.toList(),
        )
    }

    @Test
    fun `bytes that are not a public key are refused rather than throwing`() {
        val keyPair = SessionKeyPair.generate()
        assertNull(keyPair.agree(ByteArray(0)))
        assertNull(keyPair.agree(ByteArray(64) { 7 }))
        assertNull(keyPair.agree(ByteArray(4096)))
    }

    @Test
    fun `the same code and secret derive the same key on both devices`() {
        assertContentEquals(deriveSessionKey("12345678", secret), deriveSessionKey("12345678", secret))
        assertEquals(32, deriveSessionKey("12345678", secret).size)
    }

    @Test
    fun `a different code derives a different key`() {
        assertNotEquals(
            deriveSessionKey("12345678", secret).toList(),
            deriveSessionKey("12345679", secret).toList(),
        )
    }

    @Test
    fun `a different shared secret derives a different key`() {
        val otherSecret = ByteArray(32) { (it + 1).toByte() }
        assertNotEquals(
            deriveSessionKey("12345678", secret).toList(),
            deriveSessionKey("12345678", otherSecret).toList(),
        )
    }

    @Test
    fun `a payload encrypted under a key comes back under the same one`() {
        val key = deriveSessionKey("12345678", secret)
        val plaintext = "a backup, more or less".toByteArray()
        assertContentEquals(plaintext, decryptTransfer(key, transcript, encryptTransfer(key, transcript, plaintext)))
    }

    @Test
    fun `a key from a different code cannot open it`() {
        val key = deriveSessionKey("12345678", secret)
        val otherKey = deriveSessionKey("87654321", secret)
        assertNull(decryptTransfer(otherKey, transcript, encryptTransfer(key, transcript, "secret".toByteArray())))
    }

    @Test
    fun `a payload from another exchange cannot be replayed into this one`() {
        val key = deriveSessionKey("12345678", secret)
        val other = transferTranscript(ByteArray(91) { 3 }, ByteArray(91) { 4 })
        assertNull(decryptTransfer(key, other, encryptTransfer(key, transcript, "secret".toByteArray())))
    }

    @Test
    fun `a tampered block does not decrypt`() {
        val key = deriveSessionKey("12345678", secret)
        val block = encryptTransfer(key, transcript, "secret things".toByteArray())
        val tampered = block.copyOf().also { it[it.lastIndex] = (it[it.lastIndex] + 1).toByte() }
        assertNull(decryptTransfer(key, transcript, tampered))
    }

    @Test
    fun `truncated and empty blocks are refused rather than crashing`() {
        val key = deriveSessionKey("12345678", secret)
        val block = encryptTransfer(key, transcript, "secret things".toByteArray())
        assertNull(decryptTransfer(key, transcript, ByteArray(0)))
        assertNull(decryptTransfer(key, transcript, block.copyOfRange(0, 8)))
    }

    // Framing. Nothing here has been through the key exchange yet: these are the bytes any device on
    // the same network can send, and the length is a number that arrives before anything is trusted.

    @Test
    fun `a block claiming more than the caller expects is refused before it is allocated`() {
        // The block really is there to be read, which is the whole point: a stream too short to
        // deliver what it claimed is refused anyway, by the read that fails, so a test built on one
        // would pass with the limit deleted. Only an oversized block that could be read proves the
        // limit is what refused it.
        assertNull(framed(size = 2048, payload = ByteArray(2048)).readBlock(limit = 1024))
        assertNull(framed(size = 1025, payload = ByteArray(1025)).readBlock(limit = 1024))
        // Just inside it still goes through, so the line above is about the limit and not about
        // refusing everything.
        assertNotNull(framed(size = 1024, payload = ByteArray(1024)).readBlock(limit = 1024))
        // And a length nobody could ever satisfy is refused without reaching for a buffer for it.
        assertNull(framed(size = Int.MAX_VALUE, payload = ByteArray(4)).readBlock(limit = 1024))
    }

    @Test
    fun `an empty or negative length is refused`() {
        // A zero-length block is not a message, and a negative one is a length that was never written
        // by this protocol at all.
        assertNull(framed(size = 0, payload = ByteArray(0)).readBlock())
        assertNull(framed(size = -1, payload = ByteArray(4)).readBlock())
        assertNull(framed(size = Int.MIN_VALUE, payload = ByteArray(4)).readBlock())
    }

    @Test
    fun `a block that ends early is refused rather than half read`() {
        assertNull(framed(size = 32, payload = ByteArray(4)).readBlock())
        assertNull(java.io.ByteArrayInputStream(ByteArray(2)).readBlock())
        assertNull(java.io.ByteArrayInputStream(ByteArray(0)).readBlock())
    }

    @Test
    fun `what was written is what comes back`() {
        for (payload in listOf(ByteArray(1), ByteArray(1000) { it.toByte() }, "hello".toByteArray())) {
            val out = java.io.ByteArrayOutputStream()
            out.writeBlock(payload)
            assertContentEquals(payload, java.io.ByteArrayInputStream(out.toByteArray()).readBlock())
        }
    }

    /** A length header followed by [payload], so a claimed size can be tested apart from a real one. */
    private fun framed(size: Int, payload: ByteArray): java.io.InputStream {
        val header = byteArrayOf(
            ((size ushr 24) and 0xFF).toByte(),
            ((size ushr 16) and 0xFF).toByte(),
            ((size ushr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte(),
        )
        return java.io.ByteArrayInputStream(header + payload)
    }

    @Test
    fun `each encryption uses a fresh nonce`() {
        // Reusing a nonce under one key is the single thing GCM does not survive, and one session
        // key encrypts for as long as the code is on screen.
        val key = deriveSessionKey("12345678", secret)
        val plaintext = "same thing twice".toByteArray()
        assertNotEquals(
            encryptTransfer(key, transcript, plaintext).toList(),
            encryptTransfer(key, transcript, plaintext).toList(),
        )
    }

    @Test
    fun `a transcript cannot be read two ways`() {
        // Both halves carry their own length, so no two different pairs of keys land on the same
        // bytes and no proof made for one pairing is valid in another.
        assertNotEquals(
            transferTranscript(ByteArray(4) { 9 }, ByteArray(2) { 9 }).toList(),
            transferTranscript(ByteArray(2) { 9 }, ByteArray(4) { 9 }).toList(),
        )
    }

    @Test
    fun `both devices compute the same proofs`() {
        val key = deriveSessionKey("12345678", secret)
        assertContentEquals(senderConfirmation(key, transcript), senderConfirmation(key, transcript))
        assertTrue(confirms(senderConfirmation(key, transcript), senderConfirmation(key, transcript)))
        assertEquals(CONFIRMATION_SIZE, senderConfirmation(key, transcript).size)
    }

    @Test
    fun `a proof from the wrong code proves nothing`() {
        // The whole reason a device that merely announced itself never receives the data: it cannot
        // answer this without the code, whatever it heard on the network.
        val key = deriveSessionKey("12345678", secret)
        val guessed = deriveSessionKey("87654321", secret)
        assertFalse(confirms(senderConfirmation(key, transcript), senderConfirmation(guessed, transcript)))
    }

    @Test
    fun `a proof made for one exchange proves nothing in another`() {
        val key = deriveSessionKey("12345678", secret)
        val other = transferTranscript(ByteArray(91) { 3 }, ByteArray(91) { 4 })
        assertFalse(confirms(senderConfirmation(key, transcript), senderConfirmation(key, other)))
    }

    @Test
    fun `neither side can answer with the other side's proof`() {
        // Each direction is its own value, so a proof captured on the wire cannot be turned round
        // and replayed back at the device that sent it.
        val key = deriveSessionKey("12345678", secret)
        assertFalse(confirms(receiverConfirmation(key, transcript), senderConfirmation(key, transcript)))
        assertFalse(confirms(receivedConfirmation(key, transcript), receiverConfirmation(key, transcript)))
    }

    @Test
    fun `a missing or misshapen proof is refused rather than crashing`() {
        val key = deriveSessionKey("12345678", secret)
        assertFalse(confirms(senderConfirmation(key, transcript), null))
        assertFalse(confirms(senderConfirmation(key, transcript), ByteArray(0)))
        assertFalse(confirms(senderConfirmation(key, transcript), ByteArray(CONFIRMATION_SIZE)))
    }

    @Test
    fun `an announcement survives the round trip`() {
        val publicKey = SessionKeyPair.generate().publicKeyBytes
        val encoded = TransferAnnouncement(port = 45123, publicKey = publicKey).encode()
        val parsed = TransferAnnouncement.parse(encoded, encoded.size)
        assertEquals(45123, parsed?.port)
        assertContentEquals(publicKey, parsed?.publicKey)
    }

    @Test
    fun `anything that is not an announcement parses to null`() {
        val encoded = TransferAnnouncement(1, SessionKeyPair.generate().publicKeyBytes).encode()
        assertNull(TransferAnnouncement.parse(ByteArray(0), 0))
        // Truncated, so the declared key length no longer matches what is there.
        assertNull(TransferAnnouncement.parse(encoded, encoded.size - 1))
        // Right size, wrong content: no magic.
        assertNull(TransferAnnouncement.parse(ByteArray(encoded.size), encoded.size))
    }

    @Test
    fun `an announcement carries nothing derived from the code`() {
        // The reason eight digits is enough: nothing broadcast can be tested against a guess.
        val code = "12345678"
        val encoded = TransferAnnouncement(45123, SessionKeyPair.generate().publicKeyBytes).encode()
        assertEquals(-1, encoded.toString(Charsets.ISO_8859_1).indexOf(code))
    }
}

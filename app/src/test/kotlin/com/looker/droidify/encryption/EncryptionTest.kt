package com.looker.droidify.encryption

import com.looker.droidify.data.encryption.Encrypted
import com.looker.droidify.data.encryption.Key
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class EncryptionTest {

    private val secretKey = Key()
    private val fakeKey = Key()

    private val testString = "This is a test string"

    @Test
    fun `encrypt and decrypt`() {
        val (encrypted, iv) = secretKey.encrypt(testString)
        assertNotEquals(testString, encrypted.value, "Encrypted and original string are the same")
        val decrypted = encrypted.decrypt(secretKey, iv)
        assertEquals(testString, decrypted, "Decrypted string does not match original")
    }

    @Test
    fun `encrypt and decrypt with fake key`() {
        val (encrypted, iv) = secretKey.encrypt(testString)
        assertNotEquals(testString, encrypted.value, "Encrypted and original string are the same")
        assertNull(encrypted.decrypt(fakeKey, iv), "Decrypted with a key that never encrypted it")
    }

    @Test
    fun `encrypt and decrypt with wrong iv`() {
        val (encrypted, iv) = secretKey.encrypt(testString)
        assertNotEquals(testString, encrypted.value, "Encrypted and original string are the same")

        // Incremented rather than set to a constant: assigning a fixed byte leaves the nonce
        // unchanged whenever it already ended in that byte, and a nonce that isn't actually wrong
        // decrypts fine: a 1-in-256 failure the old version of this test carried.
        val fakeIv = iv.clone().apply { this[lastIndex] = (this[lastIndex] + 1).toByte() }
        assertNull(encrypted.decrypt(secretKey, fakeIv), "Decrypted under a nonce it never used")
    }

    @Test
    fun `an altered password is refused instead of decrypting to something else`() {
        // The reason for using an authenticated mode at all: without one this returns whatever the
        // altered bytes happen to decrypt to, and the caller has no way to tell.
        val (encrypted, iv) = secretKey.encrypt(testString)
        val raw = Base64.decode(encrypted.value)
        val tampered = raw.clone().apply { this[0] = (this[0] + 1).toByte() }

        assertNull(
            Encrypted(Base64.encode(tampered)).decrypt(secretKey, iv),
            "An altered ciphertext still decrypted",
        )
    }

    @Test
    fun `a password saved in the previous format still decrypts`() {
        // Someone had a repository login working before this moved to AES-GCM. Failing to read it
        // back wouldn't throw, it would silently drop their credentials on the next sync, so the old
        // format is written here by hand and has to keep opening.
        val legacyIv = ByteArray(16).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey.spec, IvParameterSpec(legacyIv))
        val legacy = Encrypted(Base64.encode(cipher.doFinal(testString.toByteArray())))

        assertEquals(testString, legacy.decrypt(secretKey, legacyIv), "Old saved password lost")
    }
}

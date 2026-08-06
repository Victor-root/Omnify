package com.looker.droidify.data.encryption

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

private const val KEY_SIZE = 256
private const val ALGORITHM = "AES"

/**
 * AES-GCM: it authenticates as well as encrypts, so a stored password that has been altered fails to
 * open instead of quietly decrypting to different bytes. The same mode [KeystoreKeyWrapper] already
 * uses on the key itself, for the same reason.
 *
 * Twelve bytes is GCM's own nonce size, and it doubles as what tells the two stored formats apart on
 * the way back in (see [LEGACY_IV_SIZE]).
 */
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val NONCE_SIZE = 12
private const val TAG_BITS = 128

/**
 * What passwords were encrypted with before, kept only for reading them back.
 *
 * CBC encrypts without authenticating, so it cannot tell an altered ciphertext from an intact one.
 * Nothing is written this way any more, but a login saved by an earlier version is still sitting in
 * the database and has to keep working: dropping it would silently log people out of repositories
 * that sync fine today. A CBC IV is a full AES block where a GCM nonce is 12 bytes, so the size of
 * the IV already stored next to a record says which of the two it is, with no schema change and
 * nothing to migrate up front. A record moves to GCM the next time its credentials are saved.
 */
private const val LEGACY_TRANSFORMATION = "AES/CBC/PKCS5Padding"
private const val LEGACY_IV_SIZE = 16

@JvmInline
value class Key(val secretKey: ByteArray) {

    val spec: SecretKeySpec
        get() = SecretKeySpec(secretKey, ALGORITHM)

    /** [input] encrypted, with the nonce it was encrypted under: the caller stores that alongside and
     *  hands it back to [Encrypted.decrypt]. A fresh one every time, since reusing a nonce under the
     *  same key is the one thing GCM does not survive. */
    fun encrypt(input: String): Pair<Encrypted, ByteArray> {
        val nonce = generateNonce()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(TAG_BITS, nonce))
        val encrypted = cipher.doFinal(input.toByteArray())
        return Encrypted(Base64.encode(encrypted)) to nonce
    }
}

fun Key() = Key(
    with(KeyGenerator.getInstance(ALGORITHM)) {
        init(KEY_SIZE)
        generateKey().encoded
    },
)

/**
 * Before encrypting we convert it to a base64 string
 * */
@JvmInline
value class Encrypted(val value: String) {

    /**
     * The original text, or null when this can't be decrypted with [key] and [iv].
     *
     * Null is a state to expect rather than an error: the app's encryption key lives in the device's
     * key store (see [KeyWrapper]) and can genuinely be gone, most plainly on a device that isn't the
     * one that saved this. Under GCM it also covers a record that no longer verifies, which is the
     * whole point of using it. What was encrypted is a repository password, so the honest answer
     * there is "this can't be read any more, ask for it again", not a crash on a sync that had
     * nothing to do with it.
     */
    fun decrypt(key: Key, iv: ByteArray): String? = runCatching {
        val isLegacy = iv.size == LEGACY_IV_SIZE
        val cipher = Cipher.getInstance(if (isLegacy) LEGACY_TRANSFORMATION else TRANSFORMATION)
        val parameters = if (isLegacy) IvParameterSpec(iv) else GCMParameterSpec(TAG_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key.spec, parameters)
        String(cipher.doFinal(Base64.decode(value)))
    }.getOrNull()
}

private fun generateNonce(): ByteArray {
    val nonce = ByteArray(NONCE_SIZE)
    val secureRandom = SecureRandom()
    secureRandom.nextBytes(nonce)
    return nonce
}

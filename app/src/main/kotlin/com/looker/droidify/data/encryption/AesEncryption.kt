package com.looker.droidify.data.encryption

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

private const val KEY_SIZE = 256
private const val IV_SIZE = 16
private const val ALGORITHM = "AES"
private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"

@JvmInline
value class Key(val secretKey: ByteArray) {

    val spec: SecretKeySpec
        get() = SecretKeySpec(secretKey, ALGORITHM)

    fun encrypt(input: String): Pair<Encrypted, ByteArray> {
        val iv = generateIV()
        val ivSpec = IvParameterSpec(iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, spec, ivSpec)
        val encrypted = cipher.doFinal(input.toByteArray())
        return Encrypted(Base64.encode(encrypted)) to iv
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
     * one that saved this. What was encrypted is a repository password, so the honest answer there is
     * "this can't be read any more, ask for it again", not a crash on a sync that had nothing to do
     * with it.
     */
    fun decrypt(key: Key, iv: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key.spec, IvParameterSpec(iv))
        String(cipher.doFinal(Base64.decode(value)))
    }.getOrNull()
}

private fun generateIV(): ByteArray {
    val iv = ByteArray(IV_SIZE)
    val secureRandom = SecureRandom()
    secureRandom.nextBytes(iv)
    return iv
}

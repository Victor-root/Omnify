package com.looker.droidify.data.encryption

import java.io.File
import java.security.MessageDigest

/**
 * SHA-256 of [data], computed on a [MessageDigest] of its own every call.
 *
 * A [MessageDigest] holds the state of whatever has been fed into it and is not thread-safe, so one
 * shared instance lets two concurrent callers interleave and walk away with a digest of neither
 * one's input. Every caller here hashes a certificate whose fingerprint an install decision rests on
 * (a repository's signing key, an app's signer), which is not something to trade for the cost of
 * allocating a digest object.
 */
fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

/** The lower-case hex SHA-256 of this file's bytes, read in chunks rather than whole into memory. */
fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

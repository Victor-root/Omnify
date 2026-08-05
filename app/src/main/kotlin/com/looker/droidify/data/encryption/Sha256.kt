package com.looker.droidify.data.encryption

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

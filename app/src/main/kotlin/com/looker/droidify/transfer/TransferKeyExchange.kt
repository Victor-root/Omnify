package com.looker.droidify.transfer

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * The curve both devices agree on. P-256 rather than anything newer purely for reach: it is the one
 * every Android back to this app's minimum (API 23) is guaranteed to have, through the platform's own
 * provider, with no library to add.
 */
private const val CURVE = "secp256r1"

/** Rough bounds on an encoded P-256 public key, so a peer cannot make this device allocate or parse
 *  something absurd before its bytes have been shown to be a key at all. */
private const val MIN_PUBLIC_KEY_BYTES = 32
internal const val MAX_PUBLIC_KEY_BYTES = 256

/**
 * One device's half of the exchange: a key pair that exists for one pairing and is thrown away with
 * it.
 *
 * This is what makes an eight-digit code defensible. Both sides publish their public half in the
 * clear, agree on a secret that never crosses the network, and the code is then stretched against
 * that secret to produce the encryption key (see [deriveSessionKey]). Someone recording every packet
 * still holds nothing to test a guessed code against, because computing any candidate key requires
 * the shared secret, and deriving that from the two public halves is the problem elliptic-curve
 * Diffie-Hellman exists to make hard.
 */
internal class SessionKeyPair(private val keyPair: KeyPair) {

    /** This device's public half, as it travels: the standard encoding, so the other side needs
     *  nothing but a key factory to read it. */
    val publicKeyBytes: ByteArray get() = keyPair.public.encoded

    /**
     * The secret this device and the owner of [peerPublicKeyBytes] arrive at independently, or null
     * when those bytes are not a public key on this curve. Hashed rather than returned raw, since the
     * raw agreement output is a curve coordinate with structure, and what the caller wants is
     * uniform key material.
     */
    fun agree(peerPublicKeyBytes: ByteArray): ByteArray? = runCatching {
        if (peerPublicKeyBytes.size !in MIN_PUBLIC_KEY_BYTES..MAX_PUBLIC_KEY_BYTES) return null
        val peerKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublicKeyBytes))
        if (!onSameCurve(peerKey)) return null
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(keyPair.private as PrivateKey)
        agreement.doPhase(peerKey, true)
        MessageDigest.getInstance("SHA-256").digest(agreement.generateSecret())
    }.getOrNull()

    /**
     * Whether a peer's key really is on the curve this session uses.
     *
     * The encoding names its own curve, so a peer is free to hand over a key on some other one, and
     * an agreement across mismatched or deliberately weak curves is a known way to strip a
     * Diffie-Hellman exchange of its secrecy. Android's provider does refuse such a key when the
     * agreement runs, but that is its guarantee rather than this code's, and this is short enough to
     * simply not depend on it.
     */
    private fun onSameCurve(peerKey: PublicKey): Boolean {
        val ours = (keyPair.public as? ECPublicKey)?.params ?: return false
        val theirs = (peerKey as? ECPublicKey)?.params ?: return false
        return theirs.curve == ours.curve &&
            theirs.generator == ours.generator &&
            theirs.order == ours.order &&
            theirs.cofactor == ours.cofactor
    }

    companion object {
        /** A fresh pair for one pairing. */
        fun generate(): SessionKeyPair = SessionKeyPair(
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE)) }.generateKeyPair(),
        )
    }
}

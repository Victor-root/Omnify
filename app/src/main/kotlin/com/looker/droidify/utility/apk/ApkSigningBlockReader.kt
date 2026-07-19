package com.looker.droidify.utility.apk

import android.util.Log
import com.looker.droidify.data.encryption.sha256
import com.looker.droidify.data.model.hex
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.RangeResult
import com.looker.droidify.network.header.HeadersBuilder

/**
 * Reads just the signer certificate fingerprint(s) out of a remote, not-yet-downloaded APK — via HTTP
 * range requests against its ZIP End-Of-Central-Directory and its APK Signing Block (the structure
 * [ApkZipLocator]/[RemoteApkLocaleReader] already read for other purposes, immediately preceding the
 * Central Directory) — instead of downloading the whole file just to answer "is what's installed under
 * this package name really signed by the key this release claims?".
 *
 * Byte layout reference: the "APK Signing Block" section of Android's APK Signature Scheme v2/v3
 * documentation (source.android.com/docs/security/features/apksigning/v2 and /v3), the same format
 * `apksig`'s own verifier walks. Deliberately reads only as far as each signer's certificate list —
 * digests, (v3) min/max SDK, additional attributes, signatures and public keys are all skipped, since
 * identity comparison only needs the certificate bytes themselves: the exact DER encoding
 * `PackageManager.GET_SIGNING_CERTIFICATES` exposes for an installed app, and what the F-Droid index's
 * own `manifest.signer.sha256` is computed from. v2 and v3 signers share the identical layout up to and
 * including the certificate list, so one parser covers both.
 *
 * Returns null whenever the block can't be found or parsed at all: a v1-only-signed APK (predates
 * scheme v2, e.g. a very old release), a malformed/truncated response, a host that doesn't support range
 * requests, a network error. Callers should treat that as *unconfirmed*, not as a mismatch — this is a
 * bonus safety signal, never a reason to block or warn on uncertainty. Never throws.
 */
object ApkSigningBlockReader {

    private const val TAG = "ApkSigningBlockReader"

    /** Same tail window [RemoteApkLocaleReader] uses to find the EOCD — comfortably covers the record
     *  plus its (near-always empty, for an APK) comment. */
    private const val TAIL_FETCH_BYTES = 128L * 1024

    /** Defensive cap on the signing block's own declared size, mirroring [RemoteApkLocaleReader]'s
     *  MAX_ARSC_BYTES — real APK signing blocks (even multi-signer, v3 key-rotation ones with long
     *  certificate chains) are at most a few hundred KB; this only guards against a corrupt or
     *  malformed size field demanding an unreasonable fetch, not a limit ever expected to bind. */
    private const val MAX_BLOCK_BYTES = 8L * 1024 * 1024

    private val SIG_BLOCK_MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
    private const val ID_V2 = 0x7109871aL
    private const val ID_V3 = 0xf05368c0L

    /**
     * SHA-256 fingerprints (lowercase hex — same format as
     * [com.looker.droidify.utility.common.extension.calculateHash] / the F-Droid index's
     * `manifest.signer.sha256`) of every signer certificate found in [apkUrl]'s APK Signing Block v2/v3
     * records, or null when the block couldn't be found/parsed (see class doc). An empty result is
     * folded into null too — a real APK always declares at least one signer, so coming back with none
     * reads as a parse gap rather than a genuine answer worth trusting.
     */
    suspend fun fetchSignerHashes(
        downloader: Downloader,
        apkUrl: String,
        headers: HeadersBuilder.() -> Unit = {},
    ): Set<String>? {
        val tail = fetchBytes(downloader, apkUrl) {
            inRangeSuffix(TAIL_FETCH_BYTES)
            headers()
        } ?: return null.also { Log.d(TAG, "$apkUrl: tail fetch failed (range not supported or network error)") }
        val centralDir = ApkZipLocator.findCentralDirectory(tail)
            ?: return null.also { Log.d(TAG, "$apkUrl: no End-Of-Central-Directory found in ${tail.size}B tail") }
        val centralDirOffset = centralDir.offset
        if (centralDirOffset < 24) return null

        // The signing block's trailing 24 bytes (its own size field, mirrored, then the fixed magic)
        // sit immediately before the Central Directory.
        val footer = fetchBytes(downloader, apkUrl) {
            inRange(centralDirOffset - 24, centralDirOffset - 1)
            headers()
        } ?: return null.also { Log.d(TAG, "$apkUrl: signing block footer fetch failed") }
        if (footer.size != 24 || !footer.copyOfRange(8, 24).contentEquals(SIG_BLOCK_MAGIC)) {
            return null.also { Log.d(TAG, "$apkUrl: no APK Signing Block found (v1-only signed APK?)") }
        }

        // This size value excludes only the block's own *leading* 8-byte size field (it's mirrored at
        // both ends so the block is also locatable scanning forward) — so the pairs section (between the
        // leading size field and this trailing 24-byte footer) is exactly (size - 24) bytes long.
        // Bounded against MAX_BLOCK_BYTES *before* any arithmetic on it below: this field is a raw
        // attacker/host-controlled uint64 that can be as large as Long.MAX_VALUE, and adding or
        // subtracting an unbounded Long can silently wrap (two's-complement overflow), defeating a
        // bounds check placed after the arithmetic instead of before it.
        val blockSize = u64(footer, 0)
        if (blockSize < 25 || blockSize > MAX_BLOCK_BYTES) {
            Log.d(TAG, "$apkUrl: signing block size out of bounds (${blockSize}B)")
            return null
        }
        val pairsLen = blockSize - 24
        val blockStart = centralDirOffset - blockSize - 8
        if (blockStart < 0) {
            Log.d(TAG, "$apkUrl: signing block would start before the file start ($blockStart)")
            return null
        }

        val pairsStart = blockStart + 8
        val pairsBytes = fetchBytes(downloader, apkUrl) {
            inRange(pairsStart, pairsStart + pairsLen - 1)
            headers()
        } ?: return null.also { Log.d(TAG, "$apkUrl: signing block pairs fetch failed") }
        if (pairsBytes.size.toLong() != pairsLen) {
            return null.also {
                Log.d(TAG, "$apkUrl: signing block pairs size mismatch (got ${pairsBytes.size}B, expected ${pairsLen}B)")
            }
        }

        val hashes = mutableSetOf<String>()
        forEachIdValuePair(pairsBytes) { id, value ->
            if (id == ID_V2 || id == ID_V3) {
                parseSignerCertificates(value).forEach { cert -> hashes += sha256(cert).hex() }
            }
        }
        Log.d(TAG, "$apkUrl: found ${hashes.size} signer fingerprint(s)")
        return hashes.ifEmpty { null }
    }

    // ---- APK Signing Block's top-level ID-value pairs: uint64-length-prefixed ----

    private fun forEachIdValuePair(pairs: ByteArray, action: (id: Long, value: ByteArray) -> Unit) {
        var pos = 0L
        val end = pairs.size.toLong()
        while (pos + 12 <= end) {
            val pairLen = u64(pairs, pos)
            // pairLen is a raw uint64 read straight off the wire and can be as large as Long.MAX_VALUE —
            // bounding it against `end` (itself always small: `pairs` is capped at MAX_BLOCK_BYTES) BEFORE
            // it takes part in any addition is required, not just belt-and-braces: `pos + 8 + pairLen`
            // below can silently wrap past Long.MAX_VALUE and land back below `end`, defeating a bounds
            // check placed after the addition instead of before it. `||` short-circuits left to right, so
            // this ordering guarantees the addition is never evaluated with an unbounded pairLen.
            if (pairLen < 4 || pairLen > end || pos + 8 + pairLen > end) return
            val id = u32(pairs, pos + 8)
            val value = pairs.copyOfRange((pos + 12).toInt(), (pos + 8 + pairLen).toInt())
            action(id, value)
            pos += 8 + pairLen
        }
    }

    // ---- APK Signature Scheme v2/v3 Block value: uint32-length-prefixed nested sequences ----

    /** [blockValue] is the v2/v3 ID-value pair's own value: a length-prefixed sequence (4-byte total
     *  length, then that many bytes of content) whose content is itself back-to-back length-prefixed
     *  "signer" records — the same two-level shape [parseCertificatesFromSignedData] unwraps one level
     *  down for the digests/certificates sequences, so the outer 4-byte length here is skipped the same
     *  way before [forEachLengthPrefixed] walks the individual signers. Returns every X.509 certificate
     *  (DER bytes) declared by every signer — v2 and v3 share the exact same layout up to and including
     *  the certificate list, the only fields this reads; whatever follows (v3's min/max SDK, additional
     *  attributes, signatures, public key) is never parsed. */
    private fun parseSignerCertificates(blockValue: ByteArray): List<ByteArray> {
        if (blockValue.size < 4) return emptyList()
        val signersLen = u32(blockValue, 0)
        if (4 + signersLen > blockValue.size) return emptyList()
        val signers = blockValue.copyOfRange(4, (4 + signersLen).toInt())
        val certs = mutableListOf<ByteArray>()
        forEachLengthPrefixed(signers) { signer ->
            if (signer.size >= 4) {
                val signedDataLen = u32(signer, 0)
                if (4 + signedDataLen <= signer.size) {
                    val signedData = signer.copyOfRange(4, (4 + signedDataLen).toInt())
                    certs += parseCertificatesFromSignedData(signedData)
                }
            }
        }
        return certs
    }

    /** [signedData] starts with a length-prefixed digests sequence, immediately followed by a
     *  length-prefixed certificates sequence (each entry itself length-prefixed X.509 DER bytes) — the
     *  only two fields read here. Whatever follows (v3's min/max SDK, additional attributes) is ignored:
     *  its own length isn't even needed since nothing after the certificates is read. */
    private fun parseCertificatesFromSignedData(signedData: ByteArray): List<ByteArray> {
        if (signedData.size < 4) return emptyList()
        val digestsLen = u32(signedData, 0)
        val certsSeqStart = 4 + digestsLen
        if (certsSeqStart + 4 > signedData.size) return emptyList()
        val certsSeqLen = u32(signedData, certsSeqStart)
        val certsStart = certsSeqStart + 4
        if (certsStart + certsSeqLen > signedData.size) return emptyList()
        val certs = mutableListOf<ByteArray>()
        val certsRange = signedData.copyOfRange(certsStart.toInt(), (certsStart + certsSeqLen).toInt())
        forEachLengthPrefixed(certsRange) { certs += it }
        return certs
    }

    /** Walks a byte range packed as back-to-back uint32-length-prefixed elements — the encoding every
     *  nested sequence inside an APK Signature Scheme v2/v3 block uses — stopping cleanly at the first
     *  malformed/truncated entry rather than throwing. */
    private fun forEachLengthPrefixed(bytes: ByteArray, action: (ByteArray) -> Unit) {
        var pos = 0L
        val end = bytes.size.toLong()
        while (pos + 4 <= end) {
            val len = u32(bytes, pos)
            if (pos + 4 + len > end) return
            action(bytes.copyOfRange((pos + 4).toInt(), (pos + 4 + len).toInt()))
            pos += 4 + len
        }
    }

    private suspend fun fetchBytes(
        downloader: Downloader,
        url: String,
        headers: HeadersBuilder.() -> Unit,
    ): ByteArray? = when (val result = downloader.getRange(url, headers)) {
        is RangeResult.Success -> result.bytes
        RangeResult.RangeNotSupported, is RangeResult.Failed -> null
    }

    /** Little-endian uint32 at [off], as a non-negative Long (0..0xFFFFFFFF) rather than a raw (signed)
     *  Int reinterpretation — a length field with the top bit set would otherwise read as negative and
     *  quietly defeat every bounds check built on top of it. Every call site validates `off` (and the
     *  4 bytes following it) against the buffer's own size before calling this. */
    private fun u32(b: ByteArray, off: Long): Long {
        val i = off.toInt()
        return (b[i].toLong() and 0xFF) or
            ((b[i + 1].toLong() and 0xFF) shl 8) or
            ((b[i + 2].toLong() and 0xFF) shl 16) or
            ((b[i + 3].toLong() and 0xFF) shl 24)
    }

    /** Little-endian uint64 at [off]. Real APK signing block size fields never come close to
     *  overflowing a signed Long, so no equivalent guard is needed here. */
    private fun u64(b: ByteArray, off: Long): Long {
        val i = off.toInt()
        var value = 0L
        for (k in 0 until 8) value = value or ((b[i + k].toLong() and 0xFF) shl (8 * k))
        return value
    }
}

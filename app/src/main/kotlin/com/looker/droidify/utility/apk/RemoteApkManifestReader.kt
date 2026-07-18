package com.looker.droidify.utility.apk

import android.util.Log
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.RangeResult
import com.looker.droidify.network.header.HeadersBuilder
import java.util.zip.Inflater

/**
 * Fetches just `AndroidManifest.xml` out of a remote, not-yet-downloaded APK — via HTTP range requests
 * against its ZIP End-Of-Central-Directory, Central Directory, and that one entry, the exact same
 * sequence [RemoteApkLocaleReader] uses for `resources.arsc` — to check whether it genuinely still
 * declares a specific component, instead of trusting a permission name alone.
 *
 * Why this exists: the F-Droid index (and any provider's release metadata) only ever exposes an app's
 * *merged permission list*, never which components actually use a given permission. A permission
 * declaration alone can outlive the code behind it — a de-Googled build made by source-patching an
 * app (rather than rebuilding its manifest) can comment out the `<service>`/`<receiver>` that used a
 * permission while leaving the `<uses-permission>` tag untouched (confirmed against a real case: a
 * de-Googled Signal fork whose Firebase Cloud Messaging receiver services are entirely removed from its
 * manifest, yet the FCM permission itself is still declared). Reading the compiled manifest directly is
 * the only way to tell the two apart from outside the app's own source.
 *
 * The bytes come back as Android's compiled binary XML, ready for [ApkBinaryManifest] to parse into
 * actual component declarations — a plain string search over these bytes is NOT enough to answer
 * "does this app still use this permission": confirmed on a real de-Googled fork whose merged manifest
 * still carries the bundled Firebase library's own generic push components (and therefore all the
 * marker strings) even though every one of the app's own push services is gone. See
 * [ApkBinaryManifest]'s doc comment for the full story.
 */
object RemoteApkManifestReader {

    private const val TAG = "RemoteApkManifestReader"
    private const val ENTRY_NAME = "AndroidManifest.xml"

    /** A compiled AndroidManifest.xml is a few KB to, at the very most, a few hundred KB for an
     *  unusually component-heavy app — this only guards against reading an unreasonable amount of data
     *  for a pathological or adversarial file rather than genuinely expecting to hit this limit. */
    private const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024

    /** Same tail window [RemoteApkLocaleReader] uses to find the EOCD. */
    private const val TAIL_FETCH_BYTES = 128L * 1024

    /**
     * The decompressed `AndroidManifest.xml` bytes of the APK at [apkUrl], or null when they couldn't
     * be read at all (the server doesn't support range requests, a network error, a malformed ZIP, the
     * entry missing/oversized/compressed with an unsupported method) — the caller should change nothing
     * in that case, the same "don't act on uncertainty" rule every other reader in this package
     * follows. Never throws.
     *
     * [expectedTotalSize] (when non-null) is compared against the remote file's real total size, read
     * from the very first range response's own Content-Range header — a mismatch aborts with null. Its
     * purpose is identity, not integrity: when [apkUrl] is a derived mirror of the file rather than the
     * repo's own address (see [RangeCapableMirrors]), an exact byte-for-byte size match against what
     * the repo index declares for the APK is what confirms the mirror actually holds the same artifact
     * and not, say, a newer build under the same name.
     */
    suspend fun fetchManifestBytes(
        downloader: Downloader,
        apkUrl: String,
        expectedTotalSize: Long? = null,
        headers: HeadersBuilder.() -> Unit = {},
    ): ByteArray? {
        val tailResult = downloader.getRange(apkUrl) {
            inRangeSuffix(TAIL_FETCH_BYTES)
            headers()
        }
        val tail = when (tailResult) {
            is RangeResult.Success -> {
                if (expectedTotalSize != null && tailResult.totalSize != expectedTotalSize) {
                    Log.d(
                        TAG,
                        "$apkUrl: total size mismatch (remote ${tailResult.totalSize}B, " +
                            "expected ${expectedTotalSize}B) — not the same artifact",
                    )
                    return null
                }
                tailResult.bytes
            }
            RangeResult.RangeNotSupported, is RangeResult.Failed ->
                return null.also { Log.d(TAG, "$apkUrl: tail fetch failed (range not supported or network error)") }
        }
        val centralDir = ApkZipLocator.findCentralDirectory(tail)
            ?: return null.also { Log.d(TAG, "$apkUrl: no End-Of-Central-Directory found in ${tail.size}B tail") }
        if (centralDir.size <= 0 || centralDir.size > MAX_MANIFEST_BYTES) {
            Log.d(TAG, "$apkUrl: central directory size out of bounds (${centralDir.size}B)")
            return null
        }

        val centralDirectoryBytes = fetchBytes(downloader, apkUrl) {
            inRange(centralDir.offset, centralDir.offset + centralDir.size - 1)
            headers()
        } ?: return null.also { Log.d(TAG, "$apkUrl: central directory fetch failed") }

        val entry = ApkZipLocator.findEntry(centralDirectoryBytes, ENTRY_NAME)
            ?: return null.also { Log.d(TAG, "$apkUrl: no $ENTRY_NAME entry in central directory") }
        if (entry.uncompressedSize <= 0 || entry.uncompressedSize > MAX_MANIFEST_BYTES) {
            Log.d(TAG, "$apkUrl: $ENTRY_NAME uncompressed size out of bounds (${entry.uncompressedSize}B)")
            return null
        }
        if (entry.compressionMethod != COMPRESSION_STORED && entry.compressionMethod != COMPRESSION_DEFLATED) {
            Log.d(TAG, "$apkUrl: unsupported compression method ${entry.compressionMethod}")
            return null
        }

        val localHeaderBytes = fetchBytes(downloader, apkUrl) {
            inRange(entry.localHeaderOffset, entry.localHeaderOffset + 29)
            headers()
        } ?: return null.also { Log.d(TAG, "$apkUrl: local file header fetch failed") }
        val dataStart = ApkZipLocator.localFileDataOffset(localHeaderBytes, entry.localHeaderOffset)
            ?: return null.also { Log.d(TAG, "$apkUrl: couldn't compute local file data offset") }

        val compressedBytes = fetchBytes(downloader, apkUrl) {
            inRange(dataStart, dataStart + entry.compressedSize - 1)
            headers()
        } ?: return null.also { Log.d(TAG, "$apkUrl: $ENTRY_NAME data fetch failed (${entry.compressedSize}B)") }

        val manifestBytes = when (entry.compressionMethod) {
            COMPRESSION_STORED -> compressedBytes
            else -> inflateRaw(compressedBytes, entry.uncompressedSize.toInt())
                ?: return null.also { Log.d(TAG, "$apkUrl: inflate failed (${compressedBytes.size}B compressed)") }
        }
        if (manifestBytes.size.toLong() != entry.uncompressedSize) {
            Log.d(
                TAG,
                "$apkUrl: decoded $ENTRY_NAME size mismatch (got ${manifestBytes.size}B, " +
                    "expected ${entry.uncompressedSize}B)",
            )
            return null
        }

        Log.d(TAG, "$apkUrl: fetched ${manifestBytes.size}B manifest")
        return manifestBytes
    }

    private suspend fun fetchBytes(
        downloader: Downloader,
        url: String,
        headers: HeadersBuilder.() -> Unit,
    ): ByteArray? = when (val result = downloader.getRange(url, headers)) {
        is RangeResult.Success -> result.bytes
        RangeResult.RangeNotSupported, is RangeResult.Failed -> null
    }

    /** Inflates raw (headerless) DEFLATE data — the "no wrap" mode matches the ZIP spec's compressed
     *  data format directly, as opposed to the zlib- or gzip-wrapped variants `Inflater` also supports. */
    private fun inflateRaw(compressed: ByteArray, expectedSize: Int): ByteArray? = runCatching {
        val inflater = Inflater(true)
        try {
            inflater.setInput(compressed)
            val output = ByteArray(expectedSize)
            var written = 0
            var stalled = false
            while (written < expectedSize && !inflater.finished() && !stalled) {
                val n = inflater.inflate(output, written, expectedSize - written)
                stalled = n == 0 && inflater.needsInput()
                written += n
            }
            if (written == expectedSize) output else null
        } finally {
            inflater.end()
        }
    }.getOrNull()

    private const val COMPRESSION_STORED = 0
    private const val COMPRESSION_DEFLATED = 8
}

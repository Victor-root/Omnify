package com.looker.droidify.external

import android.os.SystemClock
import android.util.LruCache
import com.looker.droidify.di.ApplicationScope
import com.looker.droidify.network.Downloader
import com.looker.droidify.utility.apk.ApkSigningBlockReader
import com.looker.droidify.utility.apk.RemoteApkManifestReader
import com.looker.droidify.utility.common.SingleFlight
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a source's page reads out of a remote APK, kept across pages for the life of the process.
 *
 * Each read is several range requests into the APK. A page asks for the same manifest three times over,
 * for the package id, the capability badges and the version row, and asked again each time it was opened
 * since the answers lived in that page's ViewModel.
 */
@Singleton
class RemoteApkCache @Inject constructor(
    private val downloader: Downloader,
    @param:ApplicationScope scope: CoroutineScope,
) {
    private class Memo<V : Any>(scope: CoroutineScope) {
        private val answers = LruCache<String, Pair<Long, V>>(MAX_ENTRIES)
        private val reads = SingleFlight<String, V?>(scope)

        suspend fun get(url: String, read: suspend () -> V?): V? {
            answers[url]?.let { (readAt, value) ->
                if (SystemClock.elapsedRealtime() - readAt < MAX_AGE_MS) return value
            }
            return reads.run(url) {
                read()?.also { answers.put(url, SystemClock.elapsedRealtime() to it) }
            }
        }
    }

    private val manifests = Memo<ByteArray>(scope)
    private val signers = Memo<Set<String>>(scope)

    /** See [RemoteApkManifestReader.fetchManifestBytes]. A failed read is not kept. */
    suspend fun manifest(apkUrl: String): ByteArray? =
        manifests.get(apkUrl) { RemoteApkManifestReader.fetchManifestBytes(downloader, apkUrl) }

    /** See [ApkSigningBlockReader.fetchSignerHashes]. A failed read is not kept. */
    suspend fun signers(apkUrl: String): Set<String>? =
        signers.get(apkUrl) { ApkSigningBlockReader.fetchSignerHashes(downloader, apkUrl) }
}

private const val MAX_ENTRIES = 64
private const val MAX_AGE_MS = 15 * 60 * 1000L

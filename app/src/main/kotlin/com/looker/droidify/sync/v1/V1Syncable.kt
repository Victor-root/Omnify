package com.looker.droidify.sync.v1

import android.content.Context
import com.looker.droidify.data.model.Repo
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.percentBy
import com.looker.droidify.sync.SyncState
import com.looker.droidify.sync.Syncable
import com.looker.droidify.sync.common.INDEX_V1_NAME
import com.looker.droidify.sync.common.downloadIndex
import com.looker.droidify.sync.common.toV2
import com.looker.droidify.sync.toJarScope
import com.looker.droidify.sync.v1.model.IndexV1
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class V1Syncable(
    private val context: Context,
    private val downloader: Downloader,
    private val dispatcher: CoroutineDispatcher,
) : Syncable<IndexV1> {
    override suspend fun sync(repo: Repo, block: (SyncState) -> Unit) = withContext(dispatcher) {
        try {
            val jar = downloader.downloadIndex(
                context = context,
                repo = repo,
                url = repo.address.removeSuffix("/") + "/$INDEX_V1_NAME",
                fileName = INDEX_V1_NAME,
                // Always from an empty file: a jar left by a sync that was cut short is never worth
                // resuming, and writing over it in place would shrink a file that may still be open.
                clean = true,
                onProgress = { bytes, total ->
                    val percent = (bytes percentBy total)
                    block(SyncState.IndexDownload.Progress(repo.id, percent))
                },
            )
            if (jar.length() == 0L) {
                block(
                    SyncState.IndexDownload.Failure(
                        repo.id,
                        IllegalStateException("Empty v1 index jar"),
                    ),
                )
                return@withContext
            } else {
                block(SyncState.IndexDownload.Success(repo.id))
            }
            jar.toJarScope<IndexV1>().use { scope ->
                // The jar's signing-certificate fingerprint is only known once the whole entry has
                // been read (java.util.jar.JarEntry.getCertificates() stays null until the stream is
                // fully consumed), so json() must run before fingerprint is ever read. Reading
                // fingerprint first (as this used to) always threw "Read the entry before reading
                // fingerprint" — every v1 sync failed unconditionally, for every repo, every time.
                val output = scope.json()
                val jarFingerprint = scope.fingerprint
                when {
                    jarFingerprint == null -> block(
                        SyncState.JarParsing.Failure(
                            repo.id,
                            IllegalStateException("Jar entry does not contain a fingerprint"),
                        ),
                    )

                    repo.fingerprint != null && !repo.fingerprint.assert(jarFingerprint) -> block(
                        SyncState.JarParsing.Failure(
                            repo.id,
                            IllegalStateException(
                                "Expected fingerprint: ${repo.fingerprint}, Actual fingerprint: $jarFingerprint",
                            ),
                        ),
                    )

                    else -> block(
                        SyncState.JsonParsing.Success(
                            repo.id,
                            jarFingerprint,
                            output.toV2(),
                        ),
                    )
                }
            }
            jar.delete()
        } catch (t: Throwable) {
            block(SyncState.IndexDownload.Failure(repo.id, t))
        }
    }
}

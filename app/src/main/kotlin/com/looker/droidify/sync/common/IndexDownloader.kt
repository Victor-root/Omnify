package com.looker.droidify.sync.common

import android.content.Context
import com.looker.droidify.data.model.Repo
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.NetworkResponse
import com.looker.droidify.network.ProgressListener
import com.looker.droidify.network.diagnosticLabel
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.diagnostics.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.*

suspend fun Downloader.downloadIndex(
    context: Context,
    repo: Repo,
    fileName: String,
    url: String,
    diff: Boolean = false,
    clean: Boolean = false,
    onProgress: ProgressListener? = null,
): File = withContext(Dispatchers.IO) {
    val indexFile = Cache.getIndexFile(context, "repo_${repo.id}_$fileName")
    // downloadToFile resumes by appending from the current file length — correct for an interrupted
    // release download, but wrong for a full index re-download. A *complete* index can be left in the
    // cache when the database is reset (e.g. a schema migration wipes the catalog but the cache dir
    // survives); appending the freshly downloaded index onto that stale file produces a corrupt index
    // that fails to parse, so the catalog stays empty. Callers doing a full download pass clean=true
    // to start from an empty file. This is safe here because we only reach a full download when the
    // index actually changed (the entry/diff check already handled "up to date"), so the server
    // returns the whole index, never a 304.
    DiagnosticLog.log(
        "download ${indexFile.name}: ${indexFile.length()} bytes already there, clean=$clean, diff=$diff",
    )
    if (clean) indexFile.delete()
    val response = downloadToFile(
        url = url,
        target = indexFile,
        block = onProgress,
        headers = {
            if (repo.shouldAuthenticate) {
                with(requireNotNull(repo.authentication)) {
                    authentication(
                        username = username,
                        password = password,
                    )
                }
            }
            if (repo.versionInfo != null && repo.versionInfo.timestamp > 0L && !diff) {
                ifModifiedSince(Date(repo.versionInfo.timestamp))
            }
        },
    )
    DiagnosticLog.log(
        "download ${indexFile.name}: ${response.diagnosticLabel()}, now ${indexFile.length()} bytes, " +
            "${Cache.getEmptySpace(context) / 1_000_000} MB free",
    )
    // A download cut short leaves part of the file behind, which then fails to parse as an index, or
    // worse, as a diff merged into the full index: the sync has to stop on the download's own error.
    if (response is NetworkResponse.Error) {
        val cause = (response as? NetworkResponse.Error.IO)?.exception
        throw IOException("Index download failed: ${response.diagnosticLabel()}", cause)
    }
    indexFile
}

const val INDEX_V1_NAME = "index-v1.jar"
const val ENTRY_V2_NAME = "entry.jar"
const val INDEX_V2_NAME = "index-v2.json"

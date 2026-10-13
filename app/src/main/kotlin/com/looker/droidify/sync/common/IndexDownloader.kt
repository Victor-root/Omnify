package com.looker.droidify.sync.common

import android.content.Context
import com.looker.droidify.data.model.Repo
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.NetworkResponse
import com.looker.droidify.network.ProgressListener
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.diagnostics.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
    DiagnosticLog.log("download ${indexFile.name}: ${response.describe()}, now ${indexFile.length()} bytes")
    indexFile
}

/** What a download answered, without the exception's message, which can quote a URL. */
private fun NetworkResponse.describe(): String = when (this) {
    is NetworkResponse.Success -> "HTTP $statusCode"
    is NetworkResponse.Error.Http -> "HTTP error $statusCode"
    is NetworkResponse.Error.ConnectionTimeout -> "connection timeout"
    is NetworkResponse.Error.SocketTimeout -> "socket timeout"
    is NetworkResponse.Error.IO -> "IO error ${exception.javaClass.simpleName}"
    is NetworkResponse.Error.Unknown -> "error ${exception.javaClass.simpleName}"
}

const val INDEX_V1_NAME = "index-v1.jar"
const val ENTRY_V2_NAME = "entry.jar"
const val INDEX_V2_NAME = "index-v2.json"

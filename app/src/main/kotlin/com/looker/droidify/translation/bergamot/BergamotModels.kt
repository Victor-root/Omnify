package com.looker.droidify.translation.bergamot

import android.content.Context
import android.os.SystemClock
import com.looker.droidify.BuildConfig
import com.looker.droidify.data.encryption.sha256Hex
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.NetworkResponse
import com.looker.droidify.network.diagnosticLabel
import com.looker.droidify.network.downloadToFileWithRetry
import com.looker.droidify.utility.diagnostics.DiagnosticLog
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A language pair's model files on disk, ready to load. */
internal class BergamotModelFiles(
    val source: String,
    val target: String,
    val model: File,
    val vocabs: List<File>,
    val shortlist: File,
)

/**
 * The models [BergamotTranslator] runs, downloaded from Mozilla on the first translation that needs
 * them and kept in the app's files from then on, so a pair once used translates offline. Nothing is
 * fetched ahead of time.
 */
@Singleton
class BergamotModels @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
    private val downloader: Downloader,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val root = File(context.filesDir, "translation/bergamot")
    private val lock = Mutex()
    private var catalog: BergamotCatalog? = null

    /**
     * The models to go from [source] to [target] through, in order, downloaded first when missing:
     * only the catalogue is asked, and only when the pairs on disk don't already make the route, so a
     * route used before works with no network at all.
     */
    internal suspend fun route(source: String, target: String): List<BergamotModelFiles> = lock.withLock {
        withContext(Dispatchers.IO) {
            val local = localRoute(source, target)
            if (local != null) return@withContext local
            val catalog = catalog()
            val pairs = catalog.route(source, target)
                ?: error("No offline translation model from $source to $target")
            pairs.map { (from, to) -> installed(from, to) ?: install(catalog, from, to) }
        }
    }

    private fun localRoute(source: String, target: String): List<BergamotModelFiles>? {
        if (source == target) return emptyList()
        installed(source, target)?.let { return listOf(it) }
        if (source == ENGLISH || target == ENGLISH) return null
        val first = installed(source, ENGLISH) ?: return null
        val second = installed(ENGLISH, target) ?: return null
        return listOf(first, second)
    }

    private suspend fun catalog(): BergamotCatalog = catalog ?: run {
        val response = httpClient.get(CATALOG_URL)
        check(response.status.isSuccess()) { "Model catalogue request failed (${response.status.value})" }
        json.decodeFromString(BergamotCatalog.serializer(), response.bodyAsText()).also { catalog = it }
    }

    private fun pairDir(source: String, target: String) = File(root, "$source-$target")

    /** The pair's files, when a complete download of them is on disk. */
    private fun installed(source: String, target: String): BergamotModelFiles? {
        val manifest = File(pairDir(source, target), MANIFEST).takeIf { it.isFile } ?: return null
        return runCatching {
            json.decodeFromString(InstalledPair.serializer(), manifest.readText())
                .files(pairDir(source, target), source, target)
        }.getOrNull()?.takeIf { files -> (files.vocabs + files.model + files.shortlist).all { it.isFile } }
    }

    private suspend fun install(catalog: BergamotCatalog, source: String, target: String): BergamotModelFiles {
        val entry = catalog.modelFor(source, target) ?: error("No offline translation model from $source to $target")
        val dir = pairDir(source, target)
        dir.deleteRecursively()
        dir.mkdirs()
        val startedAt = if (BuildConfig.DIAGNOSTICS) SystemClock.elapsedRealtime() else 0L
        val files = entry.files
        val model = fetch(catalog.baseUrl, files.model, dir)
        files.model.uncompressedHash?.let { expected ->
            check(model.sha256Hex().equals(expected, ignoreCase = true)) { "Downloaded model is corrupt" }
        }
        val vocabs = files.vocabs.map { fetch(catalog.baseUrl, it, dir) }
        val shortlist = fetch(catalog.baseUrl, files.lexicalShortlist, dir)
        val installed = InstalledPair(model = model.name, vocabs = vocabs.map { it.name }, shortlist = shortlist.name)
        // Written last: a pair only counts as installed once every file of it is in place.
        File(dir, MANIFEST).writeText(json.encodeToString(InstalledPair.serializer(), installed))
        if (BuildConfig.DIAGNOSTICS) {
            DiagnosticLog.log(
                "Offline translation: model $source to $target downloaded in " +
                    "${SystemClock.elapsedRealtime() - startedAt} ms, " +
                    "${dir.walk().filter { it.isFile }.sumOf { it.length() }} bytes",
            )
        }
        return installed.files(dir, source, target)
    }

    /** Downloads [file], gzipped, and unpacks it into [dir] under its own name. */
    private suspend fun fetch(baseUrl: String, file: BergamotFile, dir: File): File {
        val packed = File(context.cacheDir, "bergamot-${file.path.substringAfterLast('/')}")
        try {
            val response = downloader.downloadToFileWithRetry(
                url = baseUrl.trimEnd('/') + "/" + file.path,
                target = packed,
                label = "translation model",
            )
            check(response is NetworkResponse.Success) { "Model download failed (${response.diagnosticLabel()})" }
            val unpacked = File(dir, file.path.substringAfterLast('/').removeSuffix(".gz"))
            GZIPInputStream(packed.inputStream()).use { input ->
                unpacked.outputStream().use { input.copyTo(it) }
            }
            return unpacked
        } finally {
            packed.delete()
        }
    }
}

/** What a pair's directory holds, by file name. */
@Serializable
private data class InstalledPair(
    val model: String,
    val vocabs: List<String>,
    val shortlist: String,
) {
    fun files(dir: File, source: String, target: String) = BergamotModelFiles(
        source = source,
        target = target,
        model = File(dir, model),
        vocabs = vocabs.map { File(dir, it) },
        shortlist = File(dir, shortlist),
    )
}

private const val CATALOG_URL =
    "https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/db/models.json"
private const val MANIFEST = "installed.json"

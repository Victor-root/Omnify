package com.looker.droidify.network

import android.os.SystemClock
import com.looker.droidify.network.header.HeadersBuilder
import com.looker.droidify.network.header.KtorHeadersBuilder
import com.looker.droidify.utility.diagnostics.DiagnosticLog
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Downloads a large file in several parts at once, each over a connection of its own, the way
 * dedicated download managers do.
 *
 * A host or a network often holds each connection to a speed of its own, so several of them can go
 * faster than one, and a part that stalls holds up only itself while the others carry on. The client
 * handed in must open a real connection per request (HTTP/1.1 only): over HTTP/2 every part would share
 * one connection, and all of this would buy nothing.
 *
 * How it stays correct:
 * - The file's size, its support for ranges and a validator (a strong ETag, or else its Last-Modified
 *   date) are learned first. Every part is asked with `If-Range` on that validator, so a file replaced
 *   on the host mid-download comes back whole (200) instead of as a part of the new file, and the
 *   download is given up rather than stitched together from two versions.
 * - Each part is written at its own place in a file of the final size, retried on its own when its
 *   connection fails, and resumed from where it stopped.
 * - A part that finishes takes over half of what the slowest one has left, so the end of the file
 *   isn't left to a single slow connection.
 * - How far each part got is kept beside the file, so a download stopped altogether (the app killed,
 *   the phone restarted) resumes from there the next time it is asked for the same file.
 *
 * A host that can't be downloaded this way (no size, no ranges, no validator, or a file too small to
 * gain anything) is answered [Outcome.NotSegmentable], with nothing of this left at the target, so the
 * caller can download it the usual way.
 */
class SegmentedDownloader(
    private val client: HttpClient,
    private val dispatcher: CoroutineDispatcher,
) {

    sealed interface Outcome {
        /** Downloaded in parts, or failed for good: [response] says which. */
        data class Finished(val response: NetworkResponse) : Outcome

        /** Not for this host or this file, or the file changed on the host: download it whole. */
        data object NotSegmentable : Outcome
    }

    suspend fun download(
        url: String,
        target: File,
        headers: HeadersBuilder.() -> Unit,
        block: ProgressListener?,
        label: String,
    ): Outcome = withContext(dispatcher) {
        val probe = retryWhileTransient(
            label = "$label probe",
            isTransient = { it is Probe.Failed && it.error.isTransientFailure() },
            describe = { (it as? Probe.Failed)?.error?.diagnosticLabel().orEmpty() },
        ) { probe(url, headers) }
        when (probe) {
            is Probe.Failed -> {
                DiagnosticLog.log("$label: asking for the size failed, ${probe.error.diagnosticLabel()}")
                Outcome.Finished(probe.error)
            }

            is Probe.NotSegmentable -> {
                DiagnosticLog.log("$label: not split, ${probe.reason}")
                discard(target)
                Outcome.NotSegmentable
            }

            is Probe.Segmentable -> {
                if (probe.total < MIN_SEGMENTED_SIZE) {
                    DiagnosticLog.log("$label: not split, only ${probe.total.inMegabytes()} MB")
                    discard(target)
                    Outcome.NotSegmentable
                } else {
                    downloadInParts(url, target, headers, block, probe, label)
                }
            }
        }
    }

    /** Drops what an earlier download in parts left at [target], so a download of the usual kind
     *  doesn't take a file of the final size, holes and all, for one that has nearly finished. */
    private fun discard(target: File) {
        val state = stateFile(target)
        if (state.exists()) {
            target.delete()
            state.delete()
        }
    }

    private suspend fun downloadInParts(
        url: String,
        target: File,
        headers: HeadersBuilder.() -> Unit,
        block: ProgressListener?,
        probe: Probe.Segmentable,
        label: String,
    ): Outcome {
        val parts = resumedParts(url, target, probe) ?: freshParts(target, probe.total)
        val alreadyHere = parts.received()
        val received = AtomicLong(alreadyHere)
        DiagnosticLog.log(
            "$label: ${probe.total.inMegabytes()} MB in ${parts.size} parts over " +
                "${minOf(parts.size, MAX_CONNECTIONS)} connections, ${alreadyHere.inMegabytes()} MB already here",
        )
        val startedAt = SystemClock.elapsedRealtime()
        val result = RandomAccessFile(target, "rw").use { file ->
            try {
                coroutineScope {
                    val reporter = launch {
                        while (isActive) {
                            block?.invoke(DataSize(received.get()), DataSize(probe.total))
                            saveState(target, url, probe, parts)
                            delay(REPORT_INTERVAL_MS)
                        }
                    }
                    // One connection per part to begin with, never more than the cap: a resumed download
                    // can hold more parts than that, split off along the way.
                    List(minOf(parts.size, MAX_CONNECTIONS)) {
                        launch { runParts(url, headers, probe.validator, parts, file.channel, received, label) }
                    }.joinAll()
                    reporter.cancel()
                }
                block?.invoke(DataSize(received.get()), DataSize(probe.total))
                // Every part has finished by now, so this only fails if the parts themselves are wrong.
                // A file with a hole in it is never handed on as complete: it is downloaded whole.
                if (parts.received() == probe.total) {
                    Outcome.Finished(NetworkResponse.Success(HttpStatusCode.OK.value, null, null))
                } else {
                    DiagnosticLog.log("$label: parts add up to ${parts.received()} of ${probe.total} bytes")
                    Outcome.NotSegmentable
                }
            } catch (e: PartFailed) {
                DiagnosticLog.log("$label: a part gave up, ${e.error.diagnosticLabel()}")
                Outcome.Finished(e.error)
            } catch (e: FileChanged) {
                DiagnosticLog.log("$label: ${e.reason}, starting over whole")
                Outcome.NotSegmentable
            } finally {
                withContext(NonCancellable) { saveState(target, url, probe, parts) }
            }
        }
        when (result) {
            is Outcome.Finished -> if (result.response is NetworkResponse.Success) stateFile(target).delete()
            Outcome.NotSegmentable -> discard(target)
        }
        val ms = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1)
        val outcome = (result as? Outcome.Finished)?.response?.diagnosticLabel() ?: "falling back"
        DiagnosticLog.log(
            "$label: $outcome after ${ms / 1000} s, ${(received.get() - alreadyHere) / ms} KB/s overall, " +
                "${parts.size} parts in the end",
        )
        return result
    }

    /** Takes parts one after another until none is left, the last of them halves of slower ones. */
    private suspend fun runParts(
        url: String,
        headers: HeadersBuilder.() -> Unit,
        validator: String,
        parts: Parts,
        channel: FileChannel,
        received: AtomicLong,
        label: String,
    ) {
        while (true) {
            val part = parts.claim() ?: return
            val from = parts.nextPosition(part)
            val partLabel = "$label part at ${from.inMegabytes()} MB"
            val startedAt = SystemClock.elapsedRealtime()
            val outcome = retryWhileTransient(
                label = partLabel,
                isTransient = { it is NetworkResponse.Error && it.isTransientFailure() },
                describe = { it.diagnosticLabel() },
            ) { fetchPart(url, headers, validator, part, parts, channel, received) }
            if (outcome is NetworkResponse.Error) throw PartFailed(outcome)
            val bytes = parts.nextPosition(part) - from
            val ms = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1)
            DiagnosticLog.log("$partLabel: ${bytes.inMegabytes()} MB in ${ms / 1000} s, ${bytes / ms} KB/s")
        }
    }

    /**
     * Fetches what [part] still lacks and writes it in place. Success once the part is complete, which
     * can be before the host is done sending: another connection may have taken over the end of it.
     */
    private suspend fun fetchPart(
        url: String,
        headers: HeadersBuilder.() -> Unit,
        validator: String,
        part: Part,
        parts: Parts,
        channel: FileChannel,
        received: AtomicLong,
    ): NetworkResponse = try {
        val (from, to) = parts.remainingRange(part) ?: return NetworkResponse.Success(206, null, null)
        client.prepareGet {
            url(url)
            headers {
                val builder = KtorHeadersBuilder(this)
                builder.headers()
                builder.inRange(from, to)
                append(HttpHeaders.IfRange, validator)
            }
        }.execute { response ->
            when (response.status) {
                HttpStatusCode.PartialContent -> {
                    if (contentRangeStart(response) != from) throw FileChanged("the host sent another range")
                    writePart(response, part, parts, channel, received)
                }
                // The file on the host is no longer the one this download started on (If-Range), or a
                // range now asks past its end: either way, the parts already here can't be trusted.
                HttpStatusCode.OK -> throw FileChanged("the file changed on the host")
                HttpStatusCode.RequestedRangeNotSatisfiable -> throw FileChanged("a part asks past the end of the file")
                else -> NetworkResponse.Error.Http(response.status.value)
            }
        }
    } catch (e: Exception) {
        if (e is FileChanged) throw e
        e.asNetworkError()
    }

    private suspend fun writePart(
        response: HttpResponse,
        part: Part,
        parts: Parts,
        channel: FileChannel,
        received: AtomicLong,
    ): NetworkResponse {
        val body = response.bodyAsChannel()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = body.readAvailable(buffer)
            if (read < 0) break
            val position = parts.nextPosition(part)
            // The end can have moved closer since the request went out: what lies past it is another
            // connection's now.
            val wanted = minOf(read.toLong(), parts.remaining(part)).toInt()
            var written = 0
            while (written < wanted) {
                written += channel.write(ByteBuffer.wrap(buffer, written, wanted - written), position + written)
            }
            parts.advance(part, wanted.toLong())
            received.addAndGet(wanted.toLong())
            if (parts.remaining(part) == 0L) return NetworkResponse.Success(206, null, null)
        }
        // The host closed the connection before the part was complete: the retry resumes from here.
        return NetworkResponse.Error.IO(IOException("Part ended early"))
    }

    private suspend fun probe(url: String, headers: HeadersBuilder.() -> Unit): Probe = try {
        client.prepareGet {
            url(url)
            headers {
                val builder = KtorHeadersBuilder(this)
                builder.headers()
                builder.inRange(0, 0)
            }
        }.execute { response ->
            when {
                response.status == HttpStatusCode.PartialContent -> {
                    val total = contentRangeTotal(response)
                    val validator = validator(response)
                    when {
                        total == null -> Probe.NotSegmentable("the host gives no size")
                        validator == null -> Probe.NotSegmentable("the host gives no ETag or date to check it by")
                        else -> Probe.Segmentable(total, validator)
                    }
                }
                // Answered whole: the host doesn't take ranges. Its body is never read.
                response.status.value in 200..299 -> Probe.NotSegmentable("the host doesn't take ranges")
                else -> Probe.Failed(NetworkResponse.Error.Http(response.status.value))
            }
        }
    } catch (e: Exception) {
        Probe.Failed(e.asNetworkError())
    }

    /** The parts of an earlier download of the same file to the same [target], when everything about it
     *  still holds: same address, same size, same validator, and the file still at its full size. */
    private fun resumedParts(url: String, target: File, probe: Probe.Segmentable): Parts? {
        val state = stateFile(target)
        if (!state.exists() || target.length() != probe.total) return null
        val saved = try {
            JSON.decodeFromString(SavedState.serializer(), state.readText())
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (saved.url != url || saved.total != probe.total || saved.validator != probe.validator) {
            DiagnosticLog.log("Parts of an earlier download left aside: the file is no longer the same")
            return null
        }
        return Parts(saved.parts.map { Part(it.start, it.end, it.done) })
    }

    private fun freshParts(target: File, total: Long): Parts {
        RandomAccessFile(target, "rw").use { it.setLength(total) }
        val count = (total / MIN_PART_SIZE).coerceIn(1, MAX_CONNECTIONS.toLong()).toInt()
        val size = total / count
        return Parts(
            List(count) { index ->
                val start = index * size
                val end = if (index == count - 1) total - 1 else start + size - 1
                Part(start, end, 0)
            },
        )
    }

    private fun saveState(target: File, url: String, probe: Probe.Segmentable, parts: Parts) {
        val saved = SavedState(url, probe.total, probe.validator, parts.snapshot())
        try {
            stateFile(target).writeText(JSON.encodeToString(SavedState.serializer(), saved))
        } catch (e: IOException) {
            DiagnosticLog.log("Couldn't save the parts of a download: ${e::class.simpleName}")
        }
    }

    private fun stateFile(target: File) = File(target.path + STATE_SUFFIX)

    private sealed interface Probe {
        data class Segmentable(val total: Long, val validator: String) : Probe
        data class NotSegmentable(val reason: String) : Probe
        data class Failed(val error: NetworkResponse.Error) : Probe
    }

    private class PartFailed(val error: NetworkResponse.Error) : Exception()

    private class FileChanged(val reason: String) : Exception()

    @Serializable
    private data class SavedState(
        val url: String,
        val total: Long,
        val validator: String,
        val parts: List<SavedPart>,
    )

    @Serializable
    private data class SavedPart(val start: Long, val end: Long, val done: Long)

    private companion object {
        const val STATE_SUFFIX = ".parts"

        /** Below this, a single connection is about as fast and opening several costs more. */
        const val MIN_SEGMENTED_SIZE = 8L * 1024 * 1024

        /** No part starts smaller than this, so a file is cut into fewer parts than [MAX_CONNECTIONS]
         *  when it isn't large enough to give each one a worthwhile share. */
        const val MIN_PART_SIZE = 4L * 1024 * 1024

        /** Below this much left, a part isn't split to help it along: the new connection would take
         *  longer to get going than the part takes to finish. */
        const val MIN_SPLIT_SIZE = 1L * 1024 * 1024

        const val MAX_CONNECTIONS = 8
        const val BUFFER_SIZE = 64 * 1024
        const val REPORT_INTERVAL_MS = 200L

        val JSON = Json { ignoreUnknownKeys = true }
    }

    /** Where a part starts and ends (both included), and how much of it is already written. */
    private class Part(val start: Long, var end: Long, var done: Long, var claimed: Boolean = false)

    /**
     * The parts of one download, shared by its connections. Every read and change goes through this
     * one lock: a part's end can be moved closer by another connection taking over the rest of it.
     */
    private class Parts(initial: List<Part>) {
        private val parts = initial.toMutableList()

        val size: Int
            @Synchronized get() = parts.size

        @Synchronized
        fun received(): Long = parts.sumOf { it.done }

        @Synchronized
        fun nextPosition(part: Part): Long = part.start + part.done

        @Synchronized
        fun remaining(part: Part): Long = part.end - (part.start + part.done) + 1

        /** The range [part] still needs, or null when it is complete. */
        @Synchronized
        fun remainingRange(part: Part): Pair<Long, Long>? {
            val from = part.start + part.done
            return if (from > part.end) null else from to part.end
        }

        @Synchronized
        fun advance(part: Part, bytes: Long) {
            part.done += bytes
        }

        /** A part nobody works on that isn't complete, or else the second half of the part with the most
         *  left, split off for the caller. Null when there is nothing left worth taking. */
        @Synchronized
        fun claim(): Part? {
            parts.firstOrNull { !it.claimed && remaining(it) > 0 }?.let {
                it.claimed = true
                return it
            }
            val slowest = parts.filter { it.claimed }.maxByOrNull { remaining(it) } ?: return null
            val left = remaining(slowest)
            // At least MIN_SPLIT_SIZE stays with the part's own connection, far more than the one buffer
            // it may be writing right now from before the split: it never writes into the half taken.
            if (left < 2 * MIN_SPLIT_SIZE) return null
            val splitAt = slowest.start + slowest.done + left / 2
            val taken = Part(start = splitAt, end = slowest.end, done = 0, claimed = true)
            slowest.end = splitAt - 1
            parts += taken
            return taken
        }

        @Synchronized
        fun snapshot(): List<SavedPart> = parts.map { SavedPart(it.start, it.end, it.done) }
    }
}

private val CONTENT_RANGE = Regex("""^bytes (\d+)-(\d+)/(\d+)$""")

private fun contentRangeStart(response: HttpResponse): Long? =
    response.headers[HttpHeaders.ContentRange]?.let { CONTENT_RANGE.matchEntire(it.trim()) }
        ?.groupValues?.get(1)?.toLongOrNull()

private fun contentRangeTotal(response: HttpResponse): Long? =
    response.headers[HttpHeaders.ContentRange]?.let { CONTENT_RANGE.matchEntire(it.trim()) }
        ?.groupValues?.get(3)?.toLongOrNull()

/** What `If-Range` can hold to tell this exact file apart: a strong ETag (a weak one isn't allowed
 *  there), or else the file's Last-Modified date. Null when the host gives neither. */
private fun validator(response: HttpResponse): String? =
    response.headers[HttpHeaders.ETag]?.takeIf { !it.startsWith("W/") }
        ?: response.headers[HttpHeaders.LastModified]

/** Megabytes, one decimal, for the diagnostic log. */
private fun Long.inMegabytes(): String = "%.1f".format(Locale.ROOT, this / (1024.0 * 1024.0))

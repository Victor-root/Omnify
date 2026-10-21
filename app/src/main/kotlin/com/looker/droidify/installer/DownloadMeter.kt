package com.looker.droidify.installer

import android.os.SystemClock
import com.looker.droidify.compose.appDetail.DownloadStatus

/**
 * Turns the downloader's raw byte counts into what is shown of one download: a speed averaged over a
 * sliding window rather than off two consecutive callbacks, and a new [DownloadStatus] a few times a
 * second at most. The downloader calls back on every buffer, far more often than anything on screen
 * can use. The last tick (read has reached total) always goes through, so a bar lands on full rather
 * than stopping at whatever the last interval happened to catch.
 */
class DownloadMeter {
    private var windowStart = SystemClock.elapsedRealtime()
    private var windowStartBytes = 0L
    private var speed = 0L
    private var lastEmit = 0L

    /** What to publish for [read] of [total] bytes, or null when the last one is too recent. */
    fun sample(read: Long, total: Long?): DownloadStatus? {
        val now = SystemClock.elapsedRealtime()
        val windowMs = now - windowStart
        if (windowMs >= SPEED_WINDOW_MS) {
            speed = (read - windowStartBytes) * 1000L / windowMs
            windowStart = now
            windowStartBytes = read
        }
        val complete = total != null && read >= total
        if (now - lastEmit < EMIT_INTERVAL_MS && !complete) return null
        lastEmit = now
        return DownloadStatus(read, total ?: -1L, speed)
    }
}

private const val SPEED_WINDOW_MS = 500L

private const val EMIT_INTERVAL_MS = 150L

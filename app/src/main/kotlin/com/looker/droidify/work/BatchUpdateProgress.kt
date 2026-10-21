package com.looker.droidify.work

import com.looker.droidify.compose.appDetail.DownloadStatus
import com.looker.droidify.installer.DownloadMeter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a running "update all" says what it is doing, so the rest of the app can show it.
 *
 * [UpdateAllWorker] downloads on its own, outside any screen, and used to report nothing but which
 * package it had reached (through WorkManager's own progress). So opening an app's page while the
 * batch was downloading that very app showed an idle screen: the page only ever knew about downloads
 * its own view model had started. From the user's side the app looked like it was doing nothing, on
 * the one operation that takes the longest.
 *
 * One shared holder fixes that for every screen at once, and it can stay this simple because the
 * batch is strictly sequential: there is at most one package in flight, so there is at most one
 * state, no map to key by package.
 *
 * Deliberately in memory only, unlike the WorkManager progress that survives the process. This is
 * live byte counts for something on screen right now; if the process was restarted there is no
 * screen left that was waiting for them, and the coarse "which package" from WorkManager still
 * carries the Updates tab through.
 */
@Singleton
class BatchUpdateProgress @Inject constructor() {

    /**
     * @param position 1-based place of [packageName] in the batch, so [overallFraction] can tell how
     *   much of the whole run is behind it.
     * @param download null while the package is being installed rather than downloaded, and before
     *   its first byte arrives.
     */
    data class State(
        val packageName: String,
        val position: Int,
        val count: Int,
        val download: DownloadStatus?,
        /** versionCode of the release being fetched, known only once the batch has resolved which one
         *  this device can take, so an app's version list can mark the row actually being downloaded
         *  rather than only showing progress at the top of the page. */
        val versionCode: Long? = null,
    ) {
        /**
         * How far the whole batch has come, 0f..1f: the packages already finished, plus how far the
         * current one has downloaded. Counting the download in makes the bar move continuously
         * instead of standing still through a large APK and then jumping a whole step.
         */
        val overallFraction: Float
            get() {
                if (count <= 0) return 0f
                val finished = (position - 1).coerceAtLeast(0).toFloat()
                return ((finished + (download?.fraction ?: 0f)) / count).coerceIn(0f, 1f)
            }

        val overallPercent: Int get() = (overallFraction * 100).toInt()
    }

    private val _state = MutableStateFlow<State?>(null)

    /** The package being updated right now and how far along it is, or null when no batch is running. */
    val state: StateFlow<State?> = _state.asStateFlow()

    private var meter = DownloadMeter()

    fun startPackage(packageName: String, position: Int, count: Int) {
        meter = DownloadMeter()
        _state.value = State(packageName, position, count, null)
    }

    /** Publishes download progress for the package [startPackage] last named, as [DownloadMeter]
     *  paces it. */
    fun reportDownload(read: Long, total: Long?) {
        val status = meter.sample(read, total) ?: return
        _state.update { current -> current?.copy(download = status) }
    }

    /** Names the release the batch settled on for the current package, once it has resolved which one
     *  this device can actually take. */
    fun setTargetVersion(versionCode: Long) {
        _state.update { current -> current?.copy(versionCode = versionCode) }
    }

    /** The download is done and the install is next: keep the package on screen, drop the byte counts. */
    fun finishDownload() {
        _state.update { current -> current?.copy(download = null) }
    }

    fun clear() {
        _state.value = null
    }
}

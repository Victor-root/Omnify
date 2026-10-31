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
 * One shared holder fixes that for every screen at once. The batch works on several packages at a
 * time, so it holds one [Item] per package in flight, keyed the way the batch names them.
 *
 * Deliberately in memory only, unlike the WorkManager progress that survives the process. This is
 * live byte counts for something on screen right now; if the process was restarted there is no
 * screen left that was waiting for them, and the coarse "which packages" from WorkManager still
 * carries the Updates tab through.
 */
@Singleton
class BatchUpdateProgress @Inject constructor() {

    /**
     * @param download null while the package is being installed rather than downloaded, and before
     *   its first byte arrives.
     * @param versionCode versionCode of the release being fetched, known only once the batch has
     *   resolved which one this device can take, so an app's version list can mark the row actually
     *   being downloaded rather than only showing progress at the top of the page.
     */
    data class Item(val download: DownloadStatus? = null, val versionCode: Long? = null)

    /**
     * @param count packages in the whole batch.
     * @param finished packages already done with, updated or skipped.
     * @param items the packages in flight right now.
     */
    data class State(
        val count: Int,
        val finished: Int = 0,
        val items: Map<String, Item> = emptyMap(),
    ) {
        /** The package the run is on, counted the way the button reads: the next one to finish. */
        val position: Int get() = (finished + 1).coerceAtMost(count)

        /**
         * How far the whole batch has come, 0f..1f: the packages already finished, plus how far the
         * ones in flight have downloaded. Counting the downloads in makes the bar move continuously
         * instead of standing still through a large APK and then jumping a whole step.
         */
        val overallFraction: Float
            get() {
                if (count <= 0) return 0f
                val inFlight = items.values.sumOf { (it.download?.fraction ?: 0f).toDouble() }.toFloat()
                return ((finished + inFlight) / count).coerceIn(0f, 1f)
            }

        val overallPercent: Int get() = (overallFraction * 100).toInt()
    }

    private val _state = MutableStateFlow<State?>(null)

    /** The packages being updated right now and how far along they are, or null when no batch runs. */
    val state: StateFlow<State?> = _state.asStateFlow()

    private val meters = mutableMapOf<String, DownloadMeter>()

    fun start(count: Int) {
        synchronized(meters) { meters.clear() }
        _state.value = State(count)
    }

    fun startPackage(key: String) {
        synchronized(meters) { meters[key] = DownloadMeter() }
        _state.update { current -> current?.copy(items = current.items + (key to Item())) }
    }

    /** Publishes download progress for [key], as its [DownloadMeter] paces it. */
    fun reportDownload(key: String, read: Long, total: Long?) {
        val status = synchronized(meters) { meters[key]?.sample(read, total) } ?: return
        updateItem(key) { it.copy(download = status) }
    }

    /** Names the release the batch settled on for [key], once it has resolved which one this device
     *  can actually take. */
    fun setTargetVersion(key: String, versionCode: Long) {
        updateItem(key) { it.copy(versionCode = versionCode) }
    }

    /** [key]'s download is done and its install is next: keep it on screen, drop the byte counts. */
    fun finishDownload(key: String) {
        updateItem(key) { it.copy(download = null) }
    }

    fun finishPackage(key: String) {
        synchronized(meters) { meters.remove(key) }
        _state.update { current -> current?.copy(finished = current.finished + 1, items = current.items - key) }
    }

    fun clear() {
        synchronized(meters) { meters.clear() }
        _state.value = null
    }

    private fun updateItem(key: String, change: (Item) -> Item) {
        _state.update { current ->
            val item = current?.items?.get(key) ?: return@update current
            current.copy(items = current.items + (key to change(item)))
        }
    }
}

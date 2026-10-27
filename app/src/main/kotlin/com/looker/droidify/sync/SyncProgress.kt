package com.looker.droidify.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How far a synchronisation has come, catalogue repositories and external sources together, so the
 * header can say it in one figure. Each repository and each source weighs the same: a repository
 * counts what its index download has reached until it is saved, a source counts once it is checked.
 *
 * What has finished keeps counting, done, until everything has: the repositories are often through
 * before the sources, and dropping them would make the figure fall back.
 */
@Singleton
class SyncProgress @Inject constructor() {

    private val lock = Any()

    /** Each repository of the current synchronisation, 0f..1f. */
    private var repositories = emptyMap<Int, Float>()
    private var runningRepositories = emptySet<Int>()

    /** The external sources checked, and how many the refresh checks; null when none ran. */
    private var sources: Pair<Int, Int>? = null
    private var sourcesRunning = false

    private val _fraction = MutableStateFlow<Float?>(null)

    /** How far everything running has come, 0f..1f, or null when nothing is. */
    val fraction: StateFlow<Float?> = _fraction.asStateFlow()

    /** Counts [repoIds] in at nothing done, those already running keeping where they are. Returns the
     *  ones this added, for [finishRepositories]. */
    fun expectRepositories(repoIds: Collection<Int>): List<Int> = synchronized(lock) {
        val added = repoIds.filterNot(runningRepositories::contains)
        repositories = repositories + added.associateWith { 0f }
        runningRepositories = runningRepositories + added
        publish()
        added
    }

    fun reportRepository(repoId: Int, fraction: Float) = synchronized(lock) {
        if (repoId in runningRepositories) {
            repositories = repositories + (repoId to fraction.coerceIn(0f, 1f))
            publish()
        }
    }

    fun finishRepositories(repoIds: Collection<Int>) = synchronized(lock) {
        runningRepositories = runningRepositories - repoIds.toSet()
        repositories = repositories + repoIds.associateWith { 1f }
        publish()
    }

    fun startSources(count: Int) = synchronized(lock) {
        sources = 0 to count
        sourcesRunning = true
        publish()
    }

    fun sourceChecked() = synchronized(lock) {
        sources = sources?.let { (done, total) -> (done + 1).coerceAtMost(total) to total }
        publish()
    }

    fun finishSources() = synchronized(lock) {
        sources = sources?.let { (_, total) -> total to total }
        sourcesRunning = false
        publish()
    }

    private fun publish() {
        if (runningRepositories.isEmpty() && !sourcesRunning) {
            repositories = emptyMap()
            sources = null
            _fraction.value = null
        } else {
            _fraction.value = syncFraction(repositories.values, sources)
        }
    }
}

/** [repositories]' own progress and [sources]' (checked, total), as one fraction of all their units;
 *  null when there is nothing to count. */
internal fun syncFraction(repositories: Collection<Float>, sources: Pair<Int, Int>?): Float? {
    val units = repositories.size + (sources?.second ?: 0)
    if (units == 0) return null
    return ((repositories.sum() + (sources?.first ?: 0)) / units).coerceIn(0f, 1f)
}

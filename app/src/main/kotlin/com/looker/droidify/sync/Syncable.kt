package com.looker.droidify.sync

import com.looker.droidify.data.model.Repo

interface Syncable<T> {
    /**
     * Downloads and reads [repo]'s index. [beforeReading] is called before anything large is read into
     * memory, so a caller syncing several repositories at once can read their indexes one at a time.
     */
    suspend fun sync(repo: Repo, beforeReading: suspend () -> Unit, block: (SyncState) -> Unit)
}

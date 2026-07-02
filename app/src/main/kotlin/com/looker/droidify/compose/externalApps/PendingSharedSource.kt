package com.looker.droidify.compose.externalApps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One-shot hand-off for a link shared into the app from the system "Share" sheet (see the
 * ACTION_SEND handling in MainComposeActivity), consumed once by the sources screen to open the
 * "Add source" / "Add account" dialog pre-filled.
 *
 * A process singleton: it survives the navigation hop from the activity to the sources screen, and
 * is cleared the moment the screen reads it ([clear]) so re-reading it (recomposition, re-entry)
 * can't re-open the dialog. A one-shot rather than a persistent nav argument, which would re-fire.
 */
object PendingSharedSource {

    /** A shared link waiting to be opened: [url] plus whether it points at a whole account
     *  (owner only) rather than a single repo (owner/repo). */
    data class Share(val url: String, val isAccount: Boolean)

    private val _pending = MutableStateFlow<Share?>(null)
    val pending: StateFlow<Share?> = _pending.asStateFlow()

    /** Records a freshly shared link for the sources screen to pick up. */
    fun set(url: String, isAccount: Boolean) {
        _pending.value = Share(url, isAccount)
    }

    /** Clears the pending link once the screen has acted on it, so it can never re-trigger. */
    fun clear() {
        _pending.update { null }
    }
}

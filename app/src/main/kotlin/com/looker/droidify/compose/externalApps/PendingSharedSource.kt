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
 * A process singleton on purpose: it survives the navigation hop and an activity recreation (the
 * source screen is rebuilt several times around the add), and — crucially — it's cleared the moment
 * the screen reads it ([clear]). Driving the dialog from a persistent nav argument instead reopened
 * it every time the screen re-entered composition, which is exactly what this avoids.
 */
object PendingSharedSource {

    /** A shared link waiting to be opened: [url] plus whether it points at a whole account
     *  (owner only) rather than a single repo (owner/repo). */
    data class Share(val url: String, val isAccount: Boolean)

    private val _pending = MutableStateFlow<Share?>(null)
    val pending: StateFlow<Share?> = _pending.asStateFlow()

    /** The URL last consumed by the screen. The share intent gets re-delivered when the activity is
     *  recreated (which happens around the add on some ROMs), so the same link would be handed over
     *  again and reopen the dialog. Ignoring a repeat of the just-handled URL stops that; a genuinely
     *  different share still comes through. */
    private var lastConsumedUrl: String? = null

    /** Records a freshly shared link for the sources screen to pick up, unless it's a re-delivery of
     *  the one we just handled. */
    fun set(url: String, isAccount: Boolean) {
        if (url == lastConsumedUrl) return
        _pending.value = Share(url, isAccount)
    }

    /** Clears the pending link once the screen has acted on it, remembering it so a re-delivery of the
     *  same link can't reopen the dialog. */
    fun clear() {
        lastConsumedUrl = _pending.value?.url ?: lastConsumedUrl
        _pending.update { null }
    }
}

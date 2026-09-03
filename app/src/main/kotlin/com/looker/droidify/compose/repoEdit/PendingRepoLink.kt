package com.looker.droidify.compose.repoEdit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One-shot hand-off for a repository link opened from outside the app (an `fdroidrepo://` link in a
 * message, see the ACTION_VIEW handling in MainComposeActivity), read once by the add-a-repository
 * screen so it opens with the fields already filled in.
 *
 * Same shape and reasoning as [com.looker.droidify.compose.externalApps.PendingSharedSource]: a
 * process singleton, so it survives the hop from the activity to the screen, and cleared the moment
 * the screen reads it, so returning to that screen later can't fill a form the user has since
 * emptied on purpose.
 */
object PendingRepoLink {

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** Records a link the add screen should open pre-filled. */
    fun set(link: String) {
        _pending.value = link
    }

    /** Clears the pending link once the screen has read it, so it can never fill a form twice. */
    fun clear() {
        _pending.update { null }
    }
}

package com.looker.droidify.compose.appList

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One-shot request for the home screen to open on a particular tab, set by MainComposeActivity when
 * an intent asks for one (the "updates available" notification, whose whole point is to land on the
 * updates it just announced) and read once by whichever home screen is showing, phone or TV.
 *
 * Same shape and reasoning as [com.looker.droidify.compose.externalApps.PendingSharedSource]: the
 * activity has the intent but no reach into a screen's own tab state, which lives in a ViewModel
 * scoped to a navigation destination that may not even exist yet when the intent arrives. A process
 * singleton survives that hop. Clearing it on read is what keeps it a request rather than a setting:
 * without that, coming back to the home screen later would keep yanking the reader to a tab they had
 * since deliberately left.
 */
object PendingAppListTab {

    private val _pending = MutableStateFlow<AppTab?>(null)
    val pending: StateFlow<AppTab?> = _pending.asStateFlow()

    /** Asks the home screen to open on [tab] the next time it reads this. */
    fun request(tab: AppTab) {
        _pending.value = tab
    }

    /** Clears the request once a screen has acted on it, so it can never re-select the tab. */
    fun clear() {
        _pending.update { null }
    }
}

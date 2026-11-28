package com.looker.droidify.compose.externalApps

import com.looker.droidify.compose.components.PageTranslation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What [ChangelogDialog] shows, for a catalogue app's page as for an external one: the rendered changelog
 * and its translation. [html] null with [unavailable] false means still loading; null with [unavailable]
 * true means there is none to show.
 */
class ChangelogPage(
    private val scope: CoroutineScope,
    val translation: PageTranslation,
) {
    private val _html = MutableStateFlow<String?>(null)
    val html: StateFlow<String?> = _html.asStateFlow()
    private val _unavailable = MutableStateFlow(false)
    val unavailable: StateFlow<Boolean> = _unavailable.asStateFlow()

    private var job: Job? = null

    /** Opens on [fetch]'s changelog, translated on arrival when the auto-translate setting asks for it. */
    fun load(fetch: suspend () -> String?) {
        dismiss()
        job = scope.launch {
            val html = fetch()
            _html.value = html
            _unavailable.value = html == null
            html?.let(translation::autoTranslate)
        }
    }

    fun dismiss() {
        job?.cancel()
        translation.showOriginal()
        _html.value = null
        _unavailable.value = false
    }
}

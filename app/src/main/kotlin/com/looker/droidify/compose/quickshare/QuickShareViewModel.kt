package com.looker.droidify.compose.quickshare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.transfer.IncomingQuickShare
import com.looker.droidify.transfer.QuickShareResult
import com.looker.droidify.transfer.QuickShareService
import com.looker.droidify.transfer.TrustedDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where a quick share from this screen stands. */
sealed interface QuickShareSendState {
    data object Choosing : QuickShareSendState
    class Sending(val to: String) : QuickShareSendState
    class Done(val to: String, val result: QuickShareResult) : QuickShareSendState
}

/** Quick shares as screens see them: sending an external source to a paired device, and what paired
 *  devices sent here, waiting to be accepted. */
@HiltViewModel
class QuickShareViewModel @Inject constructor(
    private val service: QuickShareService,
) : ViewModel() {

    val devices: StateFlow<List<TrustedDevice>> = service.devices
    val incoming: StateFlow<IncomingQuickShare?> = service.incoming

    private val _sendState = MutableStateFlow<QuickShareSendState>(QuickShareSendState.Choosing)
    val sendState: StateFlow<QuickShareSendState> = _sendState.asStateFlow()

    private var sendJob: Job? = null

    fun send(app: ExternalApp, to: TrustedDevice) {
        if (sendJob?.isActive == true) return
        _sendState.value = QuickShareSendState.Sending(to.name)
        sendJob = viewModelScope.launch {
            _sendState.value = QuickShareSendState.Done(to.name, service.send(listOf(app), to))
        }
    }

    fun resetSend() {
        sendJob?.cancel()
        sendJob = null
        _sendState.value = QuickShareSendState.Choosing
    }

    fun setForeground(shown: Boolean) = service.setForeground(shown)

    fun acceptIncoming() = service.acceptIncoming()

    fun declineIncoming() = service.declineIncoming()
}

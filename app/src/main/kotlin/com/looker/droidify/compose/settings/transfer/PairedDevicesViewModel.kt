package com.looker.droidify.compose.settings.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.di.IoDispatcher
import com.looker.droidify.transfer.QuickShareService
import com.looker.droidify.transfer.TransferChannel
import com.looker.droidify.transfer.TransferClient
import com.looker.droidify.transfer.TransferFailure
import com.looker.droidify.transfer.TransferHost
import com.looker.droidify.transfer.TransferHostResult
import com.looker.droidify.transfer.TransferSendResult
import com.looker.droidify.transfer.TrustedDevice
import com.looker.droidify.transfer.formatPairingCode
import com.looker.droidify.transfer.normalisePairingCode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject

/** How long the device typing the code listens for the one showing it. */
private const val DISCOVERY_TIMEOUT_MS = 20_000L

/** Everything the pairing screen can be showing. */
sealed interface PairingState {
    /** The list of paired devices, and the two ways to pair another. */
    data object Idle : PairingState

    data object Starting : PairingState

    /** This device shows a code for the other one to type. */
    class ShowingCode(val code: String) : PairingState

    /** This device waits for the code the other one shows. [error] says why the last try failed. */
    class EnteringCode(val error: TransferFailure? = null) : PairingState

    data object Working : PairingState

    class Paired(val name: String) : PairingState

    class Failed(val reason: PairingFailure) : PairingState
}

enum class PairingFailure {
    NO_NETWORK,
    EXPIRED,
    ABANDONED,

    /** Paired, but this device could not keep the secret safely, so it is not remembered. */
    NOT_STORED,
}

/**
 * Pairing two devices once, so that later quick shares between them need no code (see
 * QuickShareService), and the list of devices paired so far.
 *
 * Pairing is the code exchange devices already use to move a backup, on a channel of its own: one
 * device shows a code, the other types it, and each comes away knowing the other's identity and a
 * secret only the two of them derived. Neither the code nor the secret is ever reused.
 */
@HiltViewModel
class PairedDevicesViewModel @Inject constructor(
    private val quickShare: QuickShareService,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    val devices: StateFlow<List<TrustedDevice>> = quickShare.devices

    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private var host: TransferHost? = null
    private var job: Job? = null

    fun forget(device: TrustedDevice) {
        viewModelScope.launch { quickShare.forget(device) }
    }

    /** Shows a code and waits for the other device to type it. */
    fun showCode() {
        stop()
        _state.value = PairingState.Starting
        job = viewModelScope.launch {
            val session = TransferHost(ioDispatcher, TransferChannel.PAIRING, reply = quickShare.ownIdentity())
            host = session
            try {
                if (!withContext(ioDispatcher) { session.bind() }) {
                    _state.value = PairingState.Failed(PairingFailure.NO_NETWORK)
                    return@launch
                }
                _state.value = PairingState.ShowingCode(formatPairingCode(session.pairingCode))
                val result = session.awaitTransfer()
                ensureActive()
                _state.value = when (result) {
                    is TransferHostResult.Received -> paired(result.payload, result.secret)
                    TransferHostResult.Abandoned -> PairingState.Failed(PairingFailure.ABANDONED)
                    TransferHostResult.Expired -> PairingState.Failed(PairingFailure.EXPIRED)
                }
            } catch (_: IOException) {
                // [stop] closing the socket, which is how a waiting session is ended (see
                // DeviceTransferViewModel.startReceiving).
            } finally {
                session.close()
            }
        }
    }

    /** Asks for the code the other device shows. */
    fun enterCode() {
        stop()
        _state.value = PairingState.EnteringCode()
    }

    /** Pairs with the device showing [typed]. */
    fun pairWith(typed: String) {
        if (job?.isActive == true) return
        val code = normalisePairingCode(typed)
        if (code == null) {
            _state.value = PairingState.EnteringCode(TransferFailure.NOT_FOUND)
            return
        }
        _state.value = PairingState.Working
        job = viewModelScope.launch {
            val result = try {
                val client = TransferClient(ioDispatcher, TransferChannel.PAIRING)
                val found = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { client.locate() }.orEmpty()
                if (found.isEmpty()) {
                    TransferSendResult.Failed(TransferFailure.NOT_FOUND)
                } else {
                    client.send(found, code, quickShare.ownIdentity(), expectReply = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                TransferSendResult.Failed(TransferFailure.UNREACHABLE)
            }
            _state.value = when (result) {
                is TransferSendResult.Sent -> result.reply
                    ?.let { paired(it, result.secret) }
                    ?: PairingState.EnteringCode(TransferFailure.UNREACHABLE)
                is TransferSendResult.Failed -> PairingState.EnteringCode(result.reason)
            }
        }
    }

    private suspend fun paired(identity: ByteArray, secret: ByteArray): PairingState =
        quickShare.completePairing(identity, secret)
            ?.let { PairingState.Paired(it.name) }
            ?: PairingState.Failed(PairingFailure.NOT_STORED)

    /** Ends whatever pairing is under way and goes back to the list. */
    fun stop() {
        host?.close()
        host = null
        job?.cancel()
        job = null
        _state.value = PairingState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        host?.close()
    }
}

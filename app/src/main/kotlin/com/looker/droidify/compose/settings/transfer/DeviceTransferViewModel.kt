package com.looker.droidify.compose.settings.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.data.backup.BackupCategory
import com.looker.droidify.data.backup.BackupInspection
import com.looker.droidify.data.backup.BackupRepository
import com.looker.droidify.di.IoDispatcher
import com.looker.droidify.transfer.TransferClient
import com.looker.droidify.transfer.TransferFailure
import com.looker.droidify.transfer.TransferHost
import com.looker.droidify.transfer.TransferHostResult
import com.looker.droidify.transfer.TransferSendResult
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

/** How long the sending device listens for the other one before telling the user nothing answered.
 *  Announcements repeat every second, so anything not found within this was never going to be. */
private const val DISCOVERY_TIMEOUT_MS = 20_000L

/** Everything the receiving screen can be showing. */
sealed interface ReceiveState {
    data object Starting : ReceiveState

    /** The code is up and the other device can now find it. */
    class ShowingCode(val code: String) : ReceiveState

    /** This device is not on a local network, so there is nothing to wait on. */
    data object NoNetwork : ReceiveState

    /** Data arrived and is waiting to be let in, showing what it turned out to contain. */
    class Confirming(val categories: Set<BackupCategory>) : ReceiveState

    /** A backup arrived and is being applied. */
    data object Applying : ReceiveState

    /** Applied. */
    data object Done : ReceiveState

    /** Data arrived and the user turned it down. Nothing was applied. */
    data object Declined : ReceiveState

    /** Too many wrong codes were tried, so the session closed itself. */
    data object Abandoned : ReceiveState

    /** Nobody came before the code stopped being valid. */
    data object Expired : ReceiveState

    /** Something arrived but could not be read, or could not be applied. */
    data object Failed : ReceiveState
}

/** Everything the sending screen can be showing. */
sealed interface SendState {
    /** Waiting for the user to type a code. [error] carries why the last attempt failed, if any. */
    class EnteringCode(val error: TransferFailure? = null) : SendState

    /** Looking for the device that code belongs to, then handing the backup over. Deliberately one
     *  state rather than two: from where the user sits it is all "it is working on it". */
    data object Working : SendState

    data object Sent : SendState
}

/**
 * Drives moving settings from one device to another over the local network.
 *
 * The whole feature rests on one rule, which is what keeps it explainable: the device receiving
 * shows a code, the device sending types it. So each screen already knows everything it needs from
 * the single choice the user made in Settings, and neither one asks anything further. There is no
 * direction to pick after connecting and no second list of checkboxes anywhere. The receiving device
 * does show what arrived before letting it in (see [ReceiveState.Confirming]), which is one button
 * rather than a second round of choices, and is there because what arrives can change how this device
 * reaches the network.
 */
@HiltViewModel
class DeviceTransferViewModel @Inject constructor(
    private val backupRepository: BackupRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _receiving = MutableStateFlow<ReceiveState>(ReceiveState.Starting)
    val receiving: StateFlow<ReceiveState> = _receiving.asStateFlow()

    private val _sending = MutableStateFlow<SendState>(SendState.EnteringCode())
    val sending: StateFlow<SendState> = _sending.asStateFlow()

    /**
     * What the sending device will hand over, exactly as choosing what to put in a backup file works:
     * the same list, the same order, the same descriptions, and the same starting selection of
     * everything but the GitHub token. A transfer is a backup that travels over the network instead
     * of through a file, so there is no reason for the choice to look different.
     */
    private val _sendCategories = MutableStateFlow(BackupCategory.entries.toSet() - BackupCategory.GITHUB_TOKEN)
    val sendCategories: StateFlow<Set<BackupCategory>> = _sendCategories.asStateFlow()

    private var host: TransferHost? = null
    private var hostJob: Job? = null
    private var sendJob: Job? = null

    /** What arrived, held between it being read and the user saying whether it may be applied. */
    private var pending: BackupInspection? = null

    fun setSendCategories(categories: Set<BackupCategory>) {
        _sendCategories.value = categories
    }

    /** Opens a session and puts a code on screen. Idempotent, so recomposition cannot start a second
     *  one alongside the first. */
    fun startReceiving() {
        if (hostJob?.isActive == true) return
        hostJob = viewModelScope.launch {
            val session = TransferHost(ioDispatcher)
            // Published before it is even bound, because closing the socket from outside is the only
            // thing that unblocks a waiting accept: cancelling this coroutine alone would leave it
            // sitting in that call forever, and the finally below would never be reached.
            host = session
            try {
                // bind() touches the network stack, so it stays off the main thread even though it is
                // quick.
                val bound = withContext(ioDispatcher) { session.bind() }
                if (!bound) {
                    _receiving.value = ReceiveState.NoNetwork
                    return@launch
                }
                _receiving.value = ReceiveState.ShowingCode(formatPairingCode(session.pairingCode))
                val result = session.awaitTransfer()
                // Checked before anything is published: the wait can have finished on its own at the
                // same moment [stopReceiving] cancelled it, and writing state is not a suspending
                // call, so without this a session the user has just replaced could still land its
                // outcome on the screen showing the new one.
                ensureActive()
                _receiving.value = when (result) {
                    is TransferHostResult.Received -> inspect(result.backup)
                    TransferHostResult.Abandoned -> ReceiveState.Abandoned
                    TransferHostResult.Expired -> ReceiveState.Expired
                }
            } catch (_: IOException) {
                // The one way this wait ever ends other than a transfer: [stopReceiving] closes the
                // socket, which is what wakes the blocked accept, and it wakes it by throwing. Left
                // uncaught it would not be a cancellation (the socket failed, it was not cancelled),
                // so a supervisor scope would hand it to the thread's default handler and take the
                // app down every time the user simply closed this screen.
            } finally {
                // Runs on every way out, including the exchange simply finishing: an open port is
                // worth closing the moment it has nothing left to wait for. It also covers being
                // cancelled in the window between binding and anyone holding a reference, where the
                // close below would otherwise have found nothing to close.
                session.close()
            }
        }
    }

    /** Reads what arrived, without applying any of it, so the screen can say what it contains. */
    private suspend fun inspect(backup: ByteArray): ReceiveState {
        val inspection = backupRepository.inspectBackupBytes(backup).getOrNull() ?: return ReceiveState.Failed
        pending = inspection
        return ReceiveState.Confirming(inspection.availableCategories)
    }

    /**
     * Applies what arrived, in full: every category the archive turned out to carry, with no second
     * list of checkboxes to work through.
     *
     * Shown before it happens rather than done on arrival, because settings are not only preferences:
     * an archive can carry a proxy, repository addresses and their credentials, all of which change
     * how this device reaches the network from the next launch onwards. Only a device that proved it
     * holds the code can get this far, so this is not the thing standing between the user and an
     * attacker; it is the user seeing what they are about to take on, in one glance and one button.
     *
     * The merge itself is the non-destructive one [BackupRepository] performs for a restored file, so
     * nothing already on this device is dropped.
     */
    fun acceptIncoming() {
        val inspection = pending ?: return
        pending = null
        viewModelScope.launch {
            _receiving.value = ReceiveState.Applying
            _receiving.value = backupRepository.restoreBackup(inspection, inspection.availableCategories).fold(
                onSuccess = { ReceiveState.Done },
                onFailure = { ReceiveState.Failed },
            )
        }
    }

    /** Turns down what arrived. It is dropped where it is, having never been applied. */
    fun declineIncoming() {
        pending = null
        _receiving.value = ReceiveState.Declined
    }

    /** Closes the session and forgets it. The code is never reused: coming back to the screen starts a
     *  genuinely new session with a new code. */
    fun stopReceiving() {
        // Closed first, then cancelled: closing is what breaks the blocking accept, so doing it the
        // other way round would leave the coroutine waiting on a socket nobody is coming to.
        host?.close()
        host = null
        hostJob?.cancel()
        hostJob = null
        pending = null
        _receiving.value = ReceiveState.Starting
    }

    /** A fresh session with a fresh code, without leaving the screen. What the user is offered when a
     *  code expired or was guessed at too often, so that neither is a dead end. */
    fun restartReceiving() {
        stopReceiving()
        startReceiving()
    }

    /**
     * Finds the device waiting with [typed] and sends this device's data to it, as one step.
     *
     * Finding and sending are not offered separately because there is nothing to decide in between:
     * the only reason to look for that device is to send to it.
     */
    fun sendTo(typed: String) {
        if (sendJob?.isActive == true) return
        val code = normalisePairingCode(typed)
        if (code == null) {
            _sending.value = SendState.EnteringCode(TransferFailure.NOT_FOUND)
            return
        }
        _sending.value = SendState.Working
        sendJob = viewModelScope.launch {
            val result = try {
                val client = TransferClient(ioDispatcher)
                val found = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { client.locate() }.orEmpty()
                if (found.isEmpty()) {
                    TransferSendResult.Failed(TransferFailure.NOT_FOUND)
                } else {
                    val backup = backupRepository.createBackupBytes(_sendCategories.value).getOrThrow()
                    client.send(found, code, backup)
                }
            } catch (e: CancellationException) {
                // Rethrown rather than folded into a failure. Writing state is not a suspending call,
                // so a cancelled coroutine would still go on to publish a failure, landing after
                // [resetSending] had already cleared the screen and leaving an error under an empty
                // code field on a dialog the user had just dismissed.
                throw e
            } catch (_: Exception) {
                TransferSendResult.Failed(TransferFailure.UNREACHABLE)
            }
            _sending.value = when (result) {
                TransferSendResult.Sent -> SendState.Sent
                is TransferSendResult.Failed -> SendState.EnteringCode(result.reason)
            }
        }
    }

    /** Puts the sending screen back to its empty code field, for a retry or a fresh start. */
    fun resetSending() {
        sendJob?.cancel()
        sendJob = null
        _sending.value = SendState.EnteringCode()
    }

    override fun onCleared() {
        super.onCleared()
        host?.close()
    }
}

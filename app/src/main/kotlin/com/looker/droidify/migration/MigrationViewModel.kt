package com.looker.droidify.migration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.BuildConfig
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.ExternalAppRepository
import com.looker.droidify.external.ExternalInstallOutcome
import com.looker.droidify.external.ExternalInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the switch between release channels needs from the user right now, if anything. */
sealed interface MigrationState {

    /** Nothing to do: the running build and the published release are the same channel, and no install
     *  of the other channel is sitting on the device. The normal case, and the only one until the
     *  first stable build ships. */
    data object None : MigrationState

    /** This is the stable build and a beta install is still on the device, holding the user's data.
     *  Offers to bring it across, then to remove the old app. */
    data class ImportFromBeta(val step: ImportStep) : MigrationState

    /** This is a beta and the published release is the stable build. It cannot be installed as an
     *  update (it is a separate app to Android), so the user is told what to do instead of being left
     *  wondering why updates stopped, and offered the install right here rather than being sent to go
     *  and find it. [installing] covers the download, which is the whole app and takes a while: without
     *  it the button would look like it did nothing until Android's own confirmation finally appeared. */
    data class MoveToStable(val installing: Boolean = false) : MigrationState

    /** This is a beta and the stable build is already installed alongside it. Everything left to do
     *  happens over there, so this says so plainly and opens it: without this step the user is left in
     *  the old app with no sign that it is the old one, looking for something else to do here. */
    data object OpenStable : MigrationState
}

enum class ImportStep {
    /** Asking whether to bring the beta's data across. */
    OFFERED,

    /** The transfer is running. */
    RUNNING,

    /** Done: offering to remove the beta app now that its data is here. */
    IMPORTED,

    /** The transfer failed; the beta app is untouched and can be tried again. */
    FAILED,
}

/**
 * Drives the one-off move from the beta channel to the stable one (see [ChannelMigration] for why the
 * two cannot simply update into each other).
 *
 * Deliberately resolved once, when the app starts, rather than watched continuously: this is a
 * transition that happens once in the life of an install, and re-evaluating it while the user is in the
 * middle of answering it would only risk pulling the prompt out from under them.
 */
@HiltViewModel
class MigrationViewModel @Inject constructor(
    private val channelMigration: ChannelMigration,
    private val externalAppRepository: ExternalAppRepository,
    private val externalInstaller: ExternalInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow<MigrationState>(MigrationState.None)
    val state: StateFlow<MigrationState> = _state

    /** Answered already, in this run of the app. The stored flag covers later launches; this covers
     *  this one, which is what the simulation needs, since it deliberately ignores the stored one. */
    private var answeredThisSession = false

    init {
        refresh()
    }

    /**
     * Re-reads the situation. Called on every return to the foreground, not only at startup, because
     * the thing being watched for changes *while the app is in the background*: the user leaves to
     * install the stable build and comes back. Resolving once at startup meant the beta they came back
     * to had nothing to say until it was force-stopped and reopened, which is not something anyone
     * would think to do.
     *
     * A prompt already on screen is left alone: there is nothing new to tell someone who is in the
     * middle of answering it, and replacing it under them would be worse than saying nothing.
     */
    fun refresh() {
        if (answeredThisSession || _state.value != MigrationState.None) return
        viewModelScope.launch { resolve() }
    }

    private suspend fun resolve() {
        if (!BuildConfig.SIMULATE_CHANNEL_SWITCH && channelMigration.dismissed()) return
        _state.value = if (ExternalApp.RUNNING_BUILD_IS_BETA) betaSideState() else stableSideState()
    }

    /** The beta: either the stable build is already here and the user belongs in it, or it has been
     *  published and they are told how to get there. */
    private suspend fun betaSideState(): MigrationState {
        if (channelMigration.otherChannelInstalled()) return MigrationState.OpenStable
        // Only worth saying anything once the stable build is actually published, which is exactly what
        // the built-in source reports (and what stops it being offered as an update).
        return if (omnifySource()?.offersOtherReleaseChannel == true) {
            MigrationState.MoveToStable()
        } else {
            MigrationState.None
        }
    }

    private suspend fun omnifySource(): ExternalApp? = externalAppRepository.apps.first()
        .firstOrNull { it.key == ExternalApp.OMNIFY_REPO_KEY }

    /**
     * Downloads and installs the stable build from right here, so the prompt that explains the switch
     * is also what carries it out.
     *
     * Goes through the same installer the background update pass uses, which also records what the
     * install left on the device — the alternative, sending the user off to find the source's own page
     * for a button, is a detour for no gain. The prompt stays up throughout: Android's confirmation
     * only appears once the download is done, and until then this is the only thing telling the user
     * anything is happening.
     */
    fun installStable() {
        if ((_state.value as? MigrationState.MoveToStable)?.installing != false) return
        _state.value = MigrationState.MoveToStable(installing = true)
        viewModelScope.launch {
            val app = omnifySource()
            val outcome = app?.let { externalInstaller.installLatest(it) }
            // Started means the installer has it: Android takes over from here, and this app's part is
            // done. Anything else leaves the prompt as it was so it can simply be tried again.
            if (outcome == ExternalInstallOutcome.STARTED) {
                answeredThisSession = true
                _state.value = MigrationState.None
            } else {
                _state.value = MigrationState.MoveToStable(installing = false)
            }
        }
    }

    /** The stable build: a beta still installed is data waiting to be collected. */
    private fun stableSideState(): MigrationState = if (channelMigration.otherChannelInstalled()) {
        MigrationState.ImportFromBeta(ImportStep.OFFERED)
    } else {
        MigrationState.None
    }

    /** Opens the stable app and stops asking here: from that point this install has nothing left to do,
     *  whatever the user does over there. */
    fun openStable() {
        channelMigration.launchStable()
        dismiss()
    }

    fun importFromBeta() {
        _state.value = MigrationState.ImportFromBeta(ImportStep.RUNNING)
        viewModelScope.launch {
            val step = if (channelMigration.importFromBeta().isSuccess) {
                ImportStep.IMPORTED
            } else {
                ImportStep.FAILED
            }
            _state.value = MigrationState.ImportFromBeta(step)
        }
    }

    fun uninstallBeta() {
        channelMigration.uninstallBeta()
        // The system prompt takes over from here, and whichever way the user answers it, this app has
        // nothing left to ask: its data is already across.
        dismiss()
    }

    /** Puts the prompt away for good. The data itself is untouched, and a beta left installed keeps
     *  working — this only stops asking. */
    fun dismiss() {
        answeredThisSession = true
        channelMigration.setDismissed()
        _state.value = MigrationState.None
    }
}

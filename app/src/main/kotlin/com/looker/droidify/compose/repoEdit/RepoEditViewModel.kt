package com.looker.droidify.compose.repoEdit

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.BuildConfig
import com.looker.droidify.R
import com.looker.droidify.data.RepoRepository
import com.looker.droidify.data.model.Fingerprint
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.NetworkResponse
import com.looker.droidify.utility.common.extension.asStateFlow
import com.looker.droidify.utility.common.extension.exceptCancellation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class RepoEditViewModel @Inject constructor(
    private val repoRepository: RepoRepository,
    private val downloader: Downloader,
) : ViewModel() {

    val addressState = TextFieldState("")
    val fingerprintState = TextFieldState("")
    val usernameState = TextFieldState("")
    val passwordState = TextFieldState("")

    private val _repoId = MutableStateFlow<Int?>(null)
    val repoId: StateFlow<Int?> = _repoId

    private val _authEnabled = MutableStateFlow(false)
    val authEnabled: StateFlow<Boolean> = _authEnabled

    private val _syncError = MutableStateFlow<RepoEditErrorState?>(null)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    /** True once the repository is actually in the database, so the screen knows it can close. */
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved

    /**
     * Every address already tracked, each without the repository path ending it may carry, so that one
     * repository can't be added twice under its two spellings.
     *
     * Part of the form's own flow rather than something read on the side: as a [StateFlow] shared only
     * while subscribed, with nothing ever subscribing to it, its value stayed the empty set forever and
     * this check silently passed everything.
     */
    private val takenAddresses = repoRepository.addresses.map { addresses ->
        addresses.map { address -> stripRepoPathSuffix(address) }.toSet()
    }

    private val formFlow = combine(
        snapshotFlow { addressState.text.toString() },
        snapshotFlow { fingerprintState.text.toString() },
        snapshotFlow { usernameState.text.toString() },
        snapshotFlow { passwordState.text.toString() },
    ) { address, fingerprint, username, password ->
        RepoForm(address, fingerprint, username, password)
    }

    val errorState = combine(
        formFlow,
        takenAddresses,
        _syncError,
    ) { form, taken, syncError ->
        RepoEditErrorState(
            addressError = addressError(form.address, taken) ?: syncError?.addressError,
            fingerprintError = fingerprintError(form.fingerprint) ?: syncError?.fingerprintError,
            usernameError = usernameError(form.username, form.password) ?: syncError?.usernameError,
            passwordError = passwordError(form.username, form.password) ?: syncError?.passwordError,
        ).also { logForm(it, form) }
    }.asStateFlow(RepoEditErrorState())

    fun loadRepo(repoId: Int) {
        viewModelScope.launch {
            _repoId.value = repoId
            val repo = repoRepository.getRepo(repoId)
            repo?.let {
                addressState.edit { this.append(it.address) }
                it.fingerprint?.let { fingerprint ->
                    fingerprintState.edit { this.append(formatFingerprint(fingerprint.value)) }
                }
                it.authentication?.let { auth ->
                    _authEnabled.value = true
                    usernameState.edit { this.append(auth.username) }
                    passwordState.edit { this.append(auth.password) }
                }
            }
        }
    }

    fun setAuthEnabled(enabled: Boolean) {
        _authEnabled.value = enabled
        if (!enabled) {
            usernameState.edit { replace(0, length, "") }
            passwordState.edit { replace(0, length, "") }
        }
    }

    /**
     * Fills the form in from a link someone was sent, with whatever that link carries.
     *
     * Only the address is ever required. A link naming the fingerprint saves copying sixty-four
     * characters across; one naming the username saves a field more; one naming the password leaves
     * nothing to type at all. Anything it doesn't name is simply left for the user to fill in, so a
     * plainer link costs typing rather than failing.
     */
    fun setFromLink(link: String) {
        val parsed = parseRepoLink(link) ?: return
        addressState.edit { replace(0, length, parsed.address) }
        parsed.fingerprint?.let { fingerprintState.edit { replace(0, length, formatFingerprint(it)) } }
        if (parsed.username == null && parsed.password == null) return
        _authEnabled.value = true
        parsed.username?.let { usernameState.edit { replace(0, length, it) } }
        parsed.password?.let { passwordState.edit { replace(0, length, it) } }
    }

    fun saveRepository(skipCheck: Boolean = false) {
        if (_isLoading.value) return

        // The normalized address, not the raw text: it is the form the field was validated as, so it
        // is the one the checks below and the saved record agree on. Null is unreachable from the
        // screen, whose buttons are both disabled while the address is unusable, and saving an address
        // the form itself calls invalid is not something to do anyway.
        val address = normalizeRepoAddress(addressState.text.toString()) ?: return
        val fingerprint = fingerprintState.text.toString().replace(" ", "")
        val username = usernameState.text.toString().takeIf { it.isNotEmpty() }
        val password = passwordState.text.toString().takeIf { it.isNotEmpty() }

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "save: skipCheck=$skipCheck address=$address " +
                    "fingerprint=${fingerprint.length} chars auth=${username != null}",
            )
        }
        // Whatever went wrong last time is about to be tried again, so it stops being the answer: kept,
        // it would keep both save buttons disabled over a problem the user has since fixed.
        _syncError.value = null
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val found = if (skipCheck) address else findRepository(address, username, password)
                if (found != null) store(found, fingerprint, username, password)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * The address the repository actually answers on, or null when it doesn't answer at all, with the
     * reason already put on the form.
     */
    private suspend fun findRepository(
        address: String,
        username: String?,
        password: String?,
    ): String? {
        val found = try {
            checkAddress(address, username, password)
        } catch (e: Exception) {
            e.exceptCancellation()
            if (BuildConfig.DEBUG) Log.d(TAG, "check: threw", e)
            reportAddressError(R.string.repository_unreachable)
            return null
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "check: settled on ${found ?: "nothing"}")
        if (found == null) reportAddressError(R.string.repository_not_found)
        return found
    }

    /**
     * Writes the repository down, and says so, so the screen can close on it.
     *
     * Waited for, rather than started and forgotten in a coroutine of its own: nothing used to observe
     * this write, so whether it worked or failed, the screen sat there unchanged with the form still
     * open and no way to tell which had happened.
     */
    private suspend fun store(
        address: String,
        fingerprint: String,
        username: String?,
        password: String?,
    ) {
        try {
            if (BuildConfig.DEBUG) Log.d(TAG, "insert: writing $address")
            val id = repoRepository.insertRepo(
                address = address,
                fingerprint = fingerprint.ifEmpty { null },
                username = username,
                password = password,
            )
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "insert: written as row $id; tracked now ${repoRepository.addresses.first()}")
            }
            // Switched on straight away, which also syncs it. A repository is added to be used, and
            // until its index has been read there is nothing to call it by but the address that was
            // typed in, so the list would show a bare URL until the user went and enabled it themselves.
            repoRepository.getRepo(id)?.let { repoRepository.enableRepository(it, enable = true) }
            _saved.value = true
        } catch (e: Exception) {
            e.exceptCancellation()
            if (BuildConfig.DEBUG) Log.d(TAG, "insert: threw", e)
            reportAddressError(R.string.repository_save_failed)
        }
    }

    /** Marks the close as handled, so leaving the screen can't fire a second time. */
    fun consumeSaved() {
        _saved.value = false
    }

    private suspend fun checkAddress(
        rawAddress: String,
        username: String?,
        password: String?,
    ): String? = withContext(Dispatchers.IO) {
        val allAddresses = REPO_ADDRESS_SUFFIXES.map { "$rawAddress/$it" } + rawAddress

        allAddresses
            .sortedBy { it.length }
            .forEach { address ->
                val url = "$address/index-v1.jar"
                val response = downloader.headCall(
                    url = url,
                    headers = {
                        if (username != null && password != null) {
                            authentication(username, password)
                        }
                    },
                )
                if (BuildConfig.DEBUG) Log.d(TAG, "probe: $url -> ${response.describe()}")
                if (response is NetworkResponse.Success) return@withContext address
            }
        null
    }

    private fun reportAddressError(@StringRes error: Int) {
        _syncError.value = _syncError.value.copy(addressError = error)
    }

    @StringRes
    private fun addressError(address: String, taken: Set<String>): Int? {
        val normalizedAddress = normalizeRepoAddress(address)
        return when {
            // Worth its own words: the field shows one line whatever it holds, so a paste carrying a
            // second one looks perfectly ordinary and "invalid address" would send the user hunting
            // through an address that really is fine.
            normalizedAddress == null && address.isNotBlank() && singleAddressLine(address) == null ->
                R.string.address_single_line
            normalizedAddress == null -> R.string.invalid_address
            stripRepoPathSuffix(normalizedAddress) in taken -> R.string.already_exists
            else -> null
        }
    }

    @StringRes
    private fun fingerprintError(fingerprint: String): Int? {
        val fin = fingerprint.replace(" ", "")
        return if (fin.isNotEmpty() && fin.length != Fingerprint.Length) {
            R.string.invalid_fingerprint_format
        } else {
            null
        }
    }

    @StringRes
    private fun usernameError(username: String, password: String): Int? = when {
        username.contains(':') -> R.string.invalid_username_format
        username.isEmpty() && password.isNotEmpty() -> R.string.username_missing
        else -> null
    }

    @StringRes
    private fun passwordError(username: String, password: String): Int? = when {
        username.isNotEmpty() && password.isEmpty() -> R.string.password_missing
        else -> null
    }

    /**
     * What the form has decided, one line per change.
     *
     * Temporary instrumentation, debug builds only. Both save buttons are disabled by *any* of these
     * four errors, and the screen names none of them beyond the field they sit under, so a report of
     * "it says the address is wrong" cannot be told apart from "the fingerprint is one character
     * short" without this. A refused address is spelled out character by character (see
     * [repoAddressDiagnosis]), since the character responsible is routinely one that draws nothing at
     * all. Remove it once the reports stop.
     */
    private fun logForm(state: RepoEditErrorState, form: RepoForm) {
        if (!BuildConfig.DEBUG) return
        val address = when (state.addressError) {
            null -> "ok"
            R.string.already_exists -> "already tracked"
            else -> "invalid"
        }
        Log.d(
            TAG,
            "form: canSave=${!state.hasError} address=$address " +
                "fingerprint=${form.fingerprint.replace(" ", "").length}/${Fingerprint.Length} " +
                "username=${form.username.length} chars password=${form.password.length} chars " +
                "authEnabled=${_authEnabled.value}",
        )
        if (state.addressError != null) Log.d(TAG, "address: ${repoAddressDiagnosis(form.address)}")
    }

    private fun formatFingerprint(fingerprint: String): String {
        return fingerprint.uppercase()
            .windowed(2, 2, true)
            .take(32)
            .joinToString(separator = " ")
    }
}

/** The one tag every line of this screen's debug trail carries, so Logcat can be filtered on it. */
private const val TAG = "OmnifyRepo"

/** What a probe answered, in a form worth reading in a log. Debug trail only. */
private fun NetworkResponse.describe(): String = when (this) {
    is NetworkResponse.Success -> "HTTP $statusCode (accepted)"
    is NetworkResponse.Error.Http -> "HTTP $statusCode (refused)"
    is NetworkResponse.Error.ConnectionTimeout -> "connection timed out"
    is NetworkResponse.Error.SocketTimeout -> "socket timed out"
    is NetworkResponse.Error.IO -> "network error: ${exception.javaClass.simpleName}: ${exception.message}"
    is NetworkResponse.Error.Unknown -> "failed: ${exception.javaClass.simpleName}: ${exception.message}"
}

/** The four fields as one value, so what the form holds is validated as one thing. */
private data class RepoForm(
    val address: String,
    val fingerprint: String,
    val username: String,
    val password: String,
)

class RepoEditErrorState(
    @get:StringRes val addressError: Int? = null,
    @get:StringRes val fingerprintError: Int? = null,
    @get:StringRes val usernameError: Int? = null,
    @get:StringRes val passwordError: Int? = null,
) {
    val hasError: Boolean =
        (addressError != null) || (fingerprintError != null) || (usernameError != null) || (passwordError != null)
}

private fun RepoEditErrorState?.copy(
    @StringRes addressError: Int? = null,
    @StringRes fingerprintError: Int? = null,
    @StringRes usernameError: Int? = null,
    @StringRes passwordError: Int? = null,
) = RepoEditErrorState(
    addressError = addressError ?: this?.addressError,
    fingerprintError = fingerprintError ?: this?.fingerprintError,
    usernameError = usernameError ?: this?.usernameError,
    passwordError = passwordError ?: this?.passwordError,
)

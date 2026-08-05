package com.looker.droidify.data.encryption

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds the one key repository passwords are encrypted with, wrapped by [keyWrapper] so its stored
 * form is useless anywhere but this device.
 *
 * Resolved once, on first use, in this order:
 *  - the wrapped key already in storage, when it still opens,
 *  - failing that, a key from an older install that was stored unwrapped, adopted as-is so passwords
 *    saved back then keep working, and written back wrapped,
 *  - failing that, a new key. Reaching here means nothing encrypted with the old one can be read any
 *    more (the wrapping key is gone, e.g. this is a different device); [Encrypted.decrypt] answers
 *    null for those and the user is asked for the password again.
 */
class EncryptionStorage(
    private val datastore: DataStore<Preferences>,
    private val keyWrapper: KeyWrapper,
    private val dispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    @Volatile
    private var resolved: Key? = null

    val key: Flow<Key> = flow { emit(resolve()) }.flowOn(dispatcher)

    private suspend fun resolve(): Key = resolved ?: mutex.withLock {
        resolved ?: load().also { resolved = it }
    }

    private suspend fun load(): Key {
        val preferences = datastore.data.first()
        preferences[WRAPPED_KEY]
            ?.let(keyWrapper::unwrap)
            ?.let { return it }
        val unwrapped = preferences[UNWRAPPED_KEY]
        return store(if (unwrapped != null) Key(unwrapped) else Key())
    }

    /**
     * Writes [key] back wrapped, and drops the unwrapped copy an older install left behind.
     *
     * When this device can't wrap at all, [key] is stored unwrapped instead of failing: that is
     * exactly what every version until now did, so an unwrappable device keeps working the way it
     * always has rather than losing saved repository logins over it.
     */
    private suspend fun store(key: Key): Key {
        val wrapped = keyWrapper.wrap(key)
        datastore.edit { preferences ->
            if (wrapped != null) {
                preferences[WRAPPED_KEY] = wrapped
                preferences.remove(UNWRAPPED_KEY)
            } else {
                preferences[UNWRAPPED_KEY] = key.secretKey
                preferences.remove(WRAPPED_KEY)
            }
        }
        return key
    }

    private companion object {
        /** Pre-wrapping name, kept so an existing install's key is found and carried over. */
        val UNWRAPPED_KEY = byteArrayPreferencesKey("encryption_secret_key")
        val WRAPPED_KEY = byteArrayPreferencesKey("encryption_secret_key_wrapped")
    }
}

package com.looker.droidify.encryption

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import com.looker.droidify.data.encryption.EncryptionStorage
import com.looker.droidify.data.encryption.Key
import com.looker.droidify.data.encryption.KeyWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Covers what happens to the key repository passwords are encrypted with, which is the part of
 * [EncryptionStorage] with real consequences: getting it wrong doesn't throw, it silently locks
 * people out of repositories they had working.
 *
 * The wrapping itself is Android's key store and can't run here (no device), so [FakeKeyWrapper]
 * stands in for it. What these tests are actually about is the decision-making around it: which key
 * gets picked up, what gets written back, and what happens when wrapping isn't available or the
 * wrapped key can't be opened any more.
 *
 * Not run on Windows, where three of these fail on the library rather than on anything they cover.
 * DataStore saves by writing a scratch file and renaming it over the real one, and a rename onto an
 * existing file is refused there while it simply replaces on every system this app ships to. Any test
 * here that writes twice therefore fails on the second write, whatever it was testing. They run in CI,
 * which is Linux, like the devices the code runs on.
 */
@DisabledOnOs(OS.WINDOWS)
class EncryptionStorageTest {

    /** Reversible stand-in for the device key store, with both directions independently breakable to
     *  play the two real-world failures: a device that can't wrap, and a wrapped key gone missing. */
    private class FakeKeyWrapper(
        val canWrap: Boolean = true,
        val canUnwrap: Boolean = true,
    ) : KeyWrapper {

        override fun wrap(key: Key): ByteArray? =
            if (canWrap) key.secretKey.mask() else null

        override fun unwrap(wrapped: ByteArray): Key? =
            if (canUnwrap) Key(wrapped.mask()) else null

        private fun ByteArray.mask() = ByteArray(size) { (this[it].toInt() xor MASK).toByte() }

        private companion object {
            const val MASK = 0x5A
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var file: File
    private lateinit var datastore: DataStore<Preferences>

    @BeforeTest
    fun setUp() {
        file = File.createTempFile("encryption", ".preferences_pb").apply { delete() }
        datastore = PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    private fun storage(wrapper: KeyWrapper = FakeKeyWrapper()) =
        EncryptionStorage(datastore, wrapper, Dispatchers.IO)

    @Test
    fun `password saved by an older install is still readable afterwards`() = runTest {
        // The whole point of the migration: someone had a repository login working, and it has to
        // keep working across the upgrade without them noticing anything at all.
        val old = Key()
        val (encrypted, iv) = old.encrypt(PASSWORD)
        datastore.edit { it[UNWRAPPED_KEY] = old.secretKey }

        val key = storage().key.first()

        assertEquals(PASSWORD, encrypted.decrypt(key, iv), "Saved password no longer decrypts")
    }

    @Test
    fun `an unwrapped key is adopted, rewritten wrapped, and the plain copy dropped`() = runTest {
        val old = Key()
        datastore.edit { it[UNWRAPPED_KEY] = old.secretKey }

        val key = storage().key.first()

        assertContentEquals(old.secretKey, key.secretKey, "Adopted a different key than the old one")
        val preferences = datastore.data.first()
        assertNull(preferences[UNWRAPPED_KEY], "Key left readable in storage")
        assertNotNull(preferences[WRAPPED_KEY], "Key not written back wrapped")
    }

    @Test
    fun `a wrapped key is read back as itself`() = runTest {
        val first = storage().key.first()
        // A second storage over the same file is a fresh app start: same file, nothing cached.
        val second = storage().key.first()

        assertContentEquals(first.secretKey, second.secretKey, "Key changed between app starts")
    }

    @Test
    fun `a new key is generated when the wrapped one cannot be opened`() = runTest {
        val lost = storage()
        lost.key.first()

        // The wrapping key is gone (restored onto another device, app data cleared): the stored key
        // is unreadable. Passwords encrypted with it are lost either way, so the one thing that must
        // not happen is refusing to produce a key at all, which would take down sync with it.
        val key = storage(FakeKeyWrapper(canUnwrap = false)).key.first()

        assertEquals(KEY_BYTES, key.secretKey.size, "No usable key produced")
    }

    @Test
    fun `a device that cannot wrap keeps storing the key as before`() = runTest {
        val key = storage(FakeKeyWrapper(canWrap = false)).key.first()

        // Losing saved logins on such a device would be a worse outcome than storing the key the way
        // every previous version already did, so it stays stored, just not wrapped.
        val preferences = datastore.data.first()
        assertContentEquals(key.secretKey, preferences[UNWRAPPED_KEY], "Key not stored at all")
        assertNull(preferences[WRAPPED_KEY], "Stored as wrapped when wrapping failed")
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        const val KEY_BYTES = 32

        /** Spelled out rather than shared with the class under test: these names are the migration.
         *  Renaming one there and here together would still lose every existing install's key. */
        val UNWRAPPED_KEY = byteArrayPreferencesKey("encryption_secret_key")
        val WRAPPED_KEY = byteArrayPreferencesKey("encryption_secret_key_wrapped")
    }
}

package com.looker.droidify.transfer

import android.util.Base64
import com.looker.droidify.data.encryption.Key
import com.looker.droidify.data.encryption.KeyWrapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom

/** Bytes in a device's identifier. Random, so it says nothing about the device or its owner. */
internal const val DEVICE_ID_BYTES = 16

/** Longest device name kept, so a peer cannot fill this device's screens or storage with one. */
internal const val MAX_DEVICE_NAME_LENGTH = 40

/** How many devices are remembered at most. A household's worth, and a bound on how many secrets a
 *  quick share is checked against before being turned away. */
internal const val MAX_TRUSTED_DEVICES = 16

/** How remembering a newly paired device went. */
enum class RememberResult {
    STORED,

    /** This device already remembers that one. */
    ALREADY_PAIRED,

    /** [MAX_TRUSTED_DEVICES] are already remembered. */
    FULL,

    /** Its secret could not be stored safely on this device, so nothing was kept. */
    NOT_STORED,
}

/**
 * A device this one remembers from a pairing, and so accepts quick shares from without a code.
 *
 * [name] is the one it gave when paired, and is what this device shows for it from then on: anything
 * it later sends is attributed by [id], so it cannot pass itself off under another device's name.
 */
class TrustedDevice(
    val id: String,
    val name: String,
    /** Known only to this device and that one (see [pairingSecret]). Never sent anywhere. */
    internal val secret: ByteArray,
    val pairedAt: Long,
)

/**
 * The devices this one has been paired with, and its own identifier.
 *
 * The secrets are the only thing here worth protecting, and each is stored wrapped by [keyWrapper],
 * under a key held in Android's own key store: the file is worth nothing off this device, and it sits
 * in the directory Android never backs up. A secret that can no longer be unwrapped (the key store
 * lost its key) drops its device, which simply has to be paired again; a device whose secret cannot be
 * wrapped at all is never remembered, rather than remembered in the clear.
 */
class TrustedDeviceStore(
    private val file: File,
    private val keyWrapper: KeyWrapper,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val mutex = Mutex()
    private var loaded = false
    private var ownId: String = ""

    private val _devices = MutableStateFlow<List<TrustedDevice>>(emptyList())
    val devices: StateFlow<List<TrustedDevice>> = _devices.asStateFlow()

    /** This device's identifier, made once and kept, so the devices it is paired with recognise it. */
    suspend fun ownId(): String = mutex.withLock {
        ensureLoaded()
        ownId
    }

    /**
     * Remembers [device], unless one with its identifier already is: a pairing never replaces another,
     * so a device cannot take over the secret and name of one already trusted here. Forgetting the old
     * pairing first is how a device is paired again.
     */
    suspend fun remember(device: TrustedDevice): RememberResult = mutex.withLock {
        ensureLoaded()
        val devices = _devices.value
        when {
            devices.any { it.id == device.id } -> RememberResult.ALREADY_PAIRED
            devices.size >= MAX_TRUSTED_DEVICES -> RememberResult.FULL
            device.seal() == null || !save(devices + device) -> RememberResult.NOT_STORED
            else -> RememberResult.STORED
        }
    }

    /** Forgets the device with [id]. Still listed afterwards when the file could not be written, so
     *  the list never shows a device as forgotten that would come back. */
    suspend fun forget(id: String) = mutex.withLock {
        ensureLoaded()
        save(_devices.value.filter { it.id != id })
    }

    /** Loads the devices, so that [devices] is filled in before anything reads it. */
    suspend fun load() = mutex.withLock { ensureLoaded() }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val stored = withContext(ioDispatcher) {
            runCatching { JSON.decodeFromString(StoredDevices.serializer(), file.readText()) }.getOrNull()
        }
        ownId = stored?.ownId?.takeIf { it.isDeviceId() } ?: newDeviceId()
        _devices.value = stored?.devices.orEmpty().mapNotNull { it.open() }
        loaded = true
        if (stored?.ownId != ownId) save(_devices.value)
    }

    /** Writes [devices] and, only once they are on disk, shows them. False when they could not be
     *  written, in which case nothing changes. */
    private suspend fun save(devices: List<TrustedDevice>): Boolean {
        val stored = StoredDevices(ownId, devices.mapNotNull { it.seal() })
        val written = withContext(ioDispatcher) {
            runCatching {
                val temporary = File(file.path + ".tmp")
                temporary.writeText(JSON.encodeToString(StoredDevices.serializer(), stored))
                temporary.renameTo(file)
            }.getOrDefault(false)
        }
        if (written) _devices.value = devices
        return written
    }

    private fun TrustedDevice.seal(): StoredDevice? {
        val wrapped = keyWrapper.wrap(Key(secret)) ?: return null
        return StoredDevice(id, name, Base64.encodeToString(wrapped, Base64.NO_WRAP), pairedAt)
    }

    private fun StoredDevice.open(): TrustedDevice? {
        if (!id.isDeviceId()) return null
        val wrapped = runCatching { Base64.decode(secret, Base64.NO_WRAP) }.getOrNull() ?: return null
        val key = keyWrapper.unwrap(wrapped) ?: return null
        return TrustedDevice(id, name.take(MAX_DEVICE_NAME_LENGTH), key.secretKey, pairedAt)
    }

    @Serializable
    private class StoredDevices(val ownId: String, val devices: List<StoredDevice> = emptyList())

    @Serializable
    private class StoredDevice(val id: String, val name: String, val secret: String, val pairedAt: Long)

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

private fun newDeviceId(): String =
    ByteArray(DEVICE_ID_BYTES).also { SecureRandom().nextBytes(it) }.toHex()

/** Whether this is an identifier [newDeviceId] could have made: what arrives from another device is
 *  checked against it before being kept or compared. */
internal fun String.isDeviceId(): Boolean =
    length == DEVICE_ID_BYTES * 2 && all { it in '0'..'9' || it in 'a'..'f' }

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

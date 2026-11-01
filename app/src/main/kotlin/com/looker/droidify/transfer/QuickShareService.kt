package com.looker.droidify.transfer

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.looker.droidify.BuildConfig
import com.looker.droidify.di.ApplicationScope
import com.looker.droidify.di.IoDispatcher
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.ExternalAppRepository
import com.looker.droidify.external.ExternalRefresher
import com.looker.droidify.utility.diagnostics.DiagnosticLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** How long to wait before trying to listen again when this device is not on a local network. */
private const val NO_NETWORK_RETRY_MS = 10_000L

/** External sources another device sent, waiting for the user to accept or refuse them. */
class IncomingQuickShare(val from: String, val apps: List<ExternalApp>)

/** What two devices tell each other while pairing: who they are, and nothing else. */
@Serializable
private class DeviceIdentity(val id: String, val name: String)

private val JSON = Json { ignoreUnknownKeys = true }

/**
 * Quick shares, both ways: sending sources to a paired device, and, while the app is on screen and at
 * least one device is paired, listening for the ones paired devices send here.
 *
 * Listening only while the app is in the foreground is deliberate: the port is open for exactly as
 * long as someone is there to see what arrives, and a device nobody is using never announces itself.
 */
@Singleton
class QuickShareService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: TrustedDeviceStore,
    private val externalAppRepository: ExternalAppRepository,
    private val externalRefresher: ExternalRefresher,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    val devices: StateFlow<List<TrustedDevice>> = store.devices

    private val foreground = MutableStateFlow(false)

    private val _incoming = MutableStateFlow<IncomingQuickShare?>(null)
    val incoming: StateFlow<IncomingQuickShare?> = _incoming.asStateFlow()

    init {
        scope.launch {
            store.load()
            combine(foreground, store.devices) { shown, devices -> shown && devices.isNotEmpty() }
                .distinctUntilChanged()
                .collectLatest { shouldListen -> if (shouldListen) listen() }
        }
    }

    /** Called as the app comes on screen and leaves it. */
    fun setForeground(shown: Boolean) {
        foreground.value = shown
    }

    /** Runs until cancelled, which [collectLatest] does as soon as the app leaves the screen or the
     *  last device is forgotten. */
    private suspend fun listen() {
        val ownId = store.ownId()
        while (true) {
            val host = QuickShareHost(
                ioDispatcher = ioDispatcher,
                ownId = ownId,
                trusted = { store.devices.value },
                onReceived = ::received,
            )
            try {
                if (withContext(ioDispatcher) { host.bind() }) host.serve()
            } finally {
                host.close()
            }
            delay(NO_NETWORK_RETRY_MS)
        }
    }

    /** Takes what [from] sent, unless it holds no source this device can add or another share is still
     *  waiting for an answer: the one on screen is never swapped for another under the user's finger. */
    private fun received(from: TrustedDevice, payload: ByteArray): Boolean {
        val apps = decodeQuickShare(payload)
        if (BuildConfig.DIAGNOSTICS) DiagnosticLog.log("Quick share: ${apps?.size ?: "unreadable"} source(s) received")
        if (apps.isNullOrEmpty()) return false
        return _incoming.compareAndSet(null, IncomingQuickShare(from.name, apps))
    }

    /** Adds [share], leaving alone the sources already followed here, and looks them up. Only the share
     *  the user answered, never one that took its place. */
    fun acceptIncoming(share: IncomingQuickShare) {
        if (!_incoming.compareAndSet(share, null)) return
        scope.launch {
            val known = externalAppRepository.getApps().mapTo(HashSet()) { it.key }
            val added = share.apps.filter { it.key !in known }
            if (added.isEmpty()) return@launch
            externalAppRepository.upsertApps(added)
            externalRefresher.refresh(force = true)
        }
    }

    fun declineIncoming(share: IncomingQuickShare) {
        _incoming.compareAndSet(share, null)
    }

    suspend fun send(apps: List<ExternalApp>, to: TrustedDevice): QuickShareResult =
        QuickShareClient(ioDispatcher).send(store.ownId(), to, encodeQuickShare(apps))

    suspend fun forget(device: TrustedDevice) {
        store.forget(device.id)
    }

    /** This device as it introduces itself while pairing. */
    suspend fun ownIdentity(): ByteArray = JSON.encodeToString(
        DeviceIdentity.serializer(),
        DeviceIdentity(store.ownId(), ownDeviceName()),
    ).toByteArray(Charsets.UTF_8)

    /**
     * The device that introduced itself as [identity] in a pairing that left [secret], or null when the
     * introduction was not one. Nothing is remembered yet: see [remember].
     */
    suspend fun pairedDevice(identity: ByteArray, secret: ByteArray): TrustedDevice? {
        val peer = runCatching {
            JSON.decodeFromString(DeviceIdentity.serializer(), identity.toString(Charsets.UTF_8))
        }.getOrNull() ?: return null
        if (!peer.id.isDeviceId() || peer.id == store.ownId()) return null
        val name = peer.name.withoutHiddenCharacters().trim().take(MAX_DEVICE_NAME_LENGTH)
        return TrustedDevice(peer.id, name.ifEmpty { peer.id.take(8) }, secret, System.currentTimeMillis())
    }

    suspend fun remember(device: TrustedDevice): RememberResult = store.remember(device)

    /** The name this device goes by in its own settings, else its model. */
    private fun ownDeviceName(): String {
        val named = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } else {
            null
        }
        return (named?.takeIf { it.isNotBlank() } ?: Build.MODEL).take(MAX_DEVICE_NAME_LENGTH)
    }
}

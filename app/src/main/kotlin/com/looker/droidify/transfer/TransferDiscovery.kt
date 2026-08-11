package com.looker.droidify.transfer

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/** The one port both sides agree on for announcements. Nothing else is exchanged over it, and it
 *  carries no payload worth intercepting (see [TransferAnnouncement]). */
private const val DISCOVERY_PORT = 47654

private val DISCOVERY_MAGIC = "OMNIFYD2".toByteArray(Charsets.US_ASCII)

/** Long enough that a device joining mid-cycle waits barely at all, short enough not to be chatter. */
private const val ANNOUNCE_INTERVAL_MS = 1000L

/** How long a blocking receive waits before coming back so the loop can notice it has been
 *  cancelled. Nothing else depends on it. */
private const val RECEIVE_TIMEOUT_MS = 500

/** After the first device answers, how much longer to listen in case a second one is also waiting.
 *  Announcements repeat every second, so this is enough to hear everyone once. */
private const val GATHER_GRACE_MS = 1200L

/** Guards the receive buffer and the parse against a packet that is not one of ours. */
private const val MAX_ANNOUNCEMENT_BYTES = 512

/**
 * How many waiting devices are collected before the search stops listening.
 *
 * There is realistically one, and a household with several televisions might make two. What this
 * bounds is the other case: announcements arrive from the network, so without a ceiling a single
 * machine repeating them under different ports would decide how much this device remembers, and how
 * many key derivations it then goes on to perform, each of which is deliberately slow.
 */
private const val MAX_HOSTS = 8

/**
 * What a waiting device repeats onto the local network so a sending device can find it.
 *
 * Carries nothing derived from the pairing code, and that is the point rather than a detail: the
 * earlier design published a fingerprint of the code so that the code alone could pick out the right
 * device, which handed anyone listening something to search a short code against offline. Now it
 * carries only a port and a public key, the sending device tries the ones it hears, and there is
 * simply nothing on the wire for a guess to be tested against. It names no device and no person
 * either, so finding your own TV never shows you, or anyone else listening, what else is around.
 */
internal class TransferAnnouncement(val port: Int, val publicKey: ByteArray) {

    fun encode(): ByteArray = DISCOVERY_MAGIC +
        byteArrayOf((port ushr 8).toByte(), port.toByte()) +
        byteArrayOf((publicKey.size ushr 8).toByte(), publicKey.size.toByte()) +
        publicKey

    companion object {
        private val HEADER = DISCOVERY_MAGIC.size + 4

        fun parse(data: ByteArray, length: Int): TransferAnnouncement? {
            if (length < HEADER || length > MAX_ANNOUNCEMENT_BYTES) return null
            if (!data.copyOfRange(0, DISCOVERY_MAGIC.size).contentEquals(DISCOVERY_MAGIC)) return null
            var offset = DISCOVERY_MAGIC.size
            val port = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            if (port !in 1..65535) return null
            offset += 2
            val keySize = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2
            if (keySize <= 0 || offset + keySize != length) return null
            return TransferAnnouncement(port, data.copyOfRange(offset, offset + keySize))
        }
    }
}

/** A device heard waiting on the local network. Whether the typed code actually belongs to it is
 *  only settled by trying (see [TransferClient]), which is why several can come back, and being one
 *  of them grants nothing on its own: an attempt hands over no data until the far end has answered
 *  for the code. */
class DiscoveredHost(
    val address: InetAddress,
    val port: Int,
    internal val publicKey: ByteArray,
)

/**
 * Repeats [announcement] onto every local network this device is on, until cancelled.
 *
 * Never throws: a network that refuses broadcasts (some routers, some captive setups) should leave
 * the pairing screen up and merely unfound, not crash the app the user is staring at.
 */
internal suspend fun announceTransfer(
    announcement: TransferAnnouncement,
    ioDispatcher: CoroutineDispatcher,
): Unit = withContext(ioDispatcher) {
    val payload = announcement.encode()
    runCatching {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            while (true) {
                coroutineContext.ensureActive()
                localBroadcastAddresses().forEach { target ->
                    runCatching {
                        socket.send(DatagramPacket(payload, payload.size, target, DISCOVERY_PORT))
                    }
                }
                delay(ANNOUNCE_INTERVAL_MS)
            }
        }
    }
}

/**
 * Every device heard waiting on this network, once at least one has answered.
 *
 * Returns a list rather than the single right one because nothing on the wire says which device the
 * typed code belongs to any more: that is settled by attempting the exchange. Listening continues a
 * moment past the first answer so that a second waiting device is offered too, instead of the search
 * silently stopping at whichever happened to broadcast first.
 */
internal suspend fun findTransferHosts(ioDispatcher: CoroutineDispatcher): List<DiscoveredHost> =
    withContext(ioDispatcher) {
        val found = LinkedHashMap<String, DiscoveredHost>()
        runCatching {
            // Reuse allowed because both devices may be running this app, and the one waiting is
            // sending from its own socket while this one listens on the shared port.
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.broadcast = true
                socket.bind(InetSocketAddress(DISCOVERY_PORT))
                socket.soTimeout = RECEIVE_TIMEOUT_MS
                val buffer = ByteArray(MAX_ANNOUNCEMENT_BYTES)
                var deadline: Long? = null
                while (found.size < MAX_HOSTS && (deadline == null || System.currentTimeMillis() < deadline)) {
                    coroutineContext.ensureActive()
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    // A device that is not on this device's own network is not one this code could
                    // belong to, and is not worth an attempt.
                    if (!isOnSameLocalNetwork(packet.address)) continue
                    val announcement = TransferAnnouncement.parse(packet.data, packet.length) ?: continue
                    // Keyed by the device rather than by the port it named, and kept as first heard:
                    // one device is waiting on one port, so anything announcing several is not that,
                    // and letting it in would be letting one machine spend the whole ceiling above.
                    val key = packet.address.hostAddress ?: continue
                    if (key !in found) {
                        found[key] = DiscoveredHost(packet.address, announcement.port, announcement.publicKey)
                    }
                    if (deadline == null) deadline = System.currentTimeMillis() + GATHER_GRACE_MS
                }
            }
        }
        found.values.toList()
    }

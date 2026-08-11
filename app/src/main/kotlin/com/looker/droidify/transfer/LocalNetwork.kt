package com.looker.droidify.transfer

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * One usable IPv4 address of a local network interface, together with the size of the network it sits
 * on, which is what makes "is that peer on my network?" answerable rather than guessed (see
 * [isOnSameLocalNetwork]).
 */
private data class LocalInterfaceAddress(
    val address: Inet4Address,
    val prefixLength: Short,
    val interfaceName: String,
)

/**
 * Interface name prefixes that are never the local network a device transfer should run over, even
 * when they carry a private address and are perfectly up:
 *
 * - `tun`/`tap`/`ppp`/`wg`/`ipsec` are VPN tunnels. A VPN is the exact case where a private-looking
 *   address is NOT the network the TV in the same room is on, and where announcing such an address
 *   would reach either nothing at all or, worse, a stranger's machine inside the same tunnel.
 * - `rmnet`/`ccmni`/`pdp` are mobile data. There is no local network there to speak of, and carrier
 *   CGNAT space can look private while being shared with every other subscriber.
 */
private val EXCLUDED_INTERFACE_PREFIXES = listOf(
    "tun", "tap", "ppp", "wg", "ipsec", "rmnet", "ccmni", "pdp", "dummy",
)

/** Interface name prefixes worth preferring, best first, when a device has more than one candidate. */
private val PREFERRED_INTERFACE_PREFIXES = listOf("wlan", "eth", "ap", "p2p")

/**
 * The address this device should listen on and announce for a transfer, or null when it isn't on any
 * local network right now.
 *
 * Wi-Fi and Ethernet are preferred over anything else rather than simply taking the first interface
 * that answers: the ordering [NetworkInterface.getNetworkInterfaces] returns is the system's, not a
 * ranking, and a device can easily hold several private addresses at once.
 */
fun localTransferAddress(): Inet4Address? = usableLocalAddresses()
    .minByOrNull { candidate ->
        val preference = PREFERRED_INTERFACE_PREFIXES.indexOfFirst { candidate.interfaceName.startsWith(it) }
        if (preference >= 0) preference else PREFERRED_INTERFACE_PREFIXES.size
    }
    ?.address

/**
 * Where to send an announcement so every device on this device's own networks hears it, and nothing
 * beyond them does.
 *
 * A subnet's own broadcast address (192.168.1.255 for a typical home network) is the deliberate
 * choice over the all-networks 255.255.255.255: it is bounded by the same interface prefix that
 * [isOnSameLocalNetwork] enforces on the other side, so an announcement cannot reach further than a
 * device that would be allowed to answer it. Interfaces excluded above never contribute one, so
 * nothing is announced into a VPN tunnel.
 */
internal fun localBroadcastAddresses(): List<InetAddress> = runCatching {
    NetworkInterface.getNetworkInterfaces()
        ?.toList()
        .orEmpty()
        .filter { networkInterface ->
            runCatching { networkInterface.isUp && !networkInterface.isLoopback }.getOrDefault(false) &&
                EXCLUDED_INTERFACE_PREFIXES.none { networkInterface.name.startsWith(it) }
        }
        .flatMap { networkInterface -> networkInterface.interfaceAddresses.mapNotNull { it.broadcast } }
        .distinct()
}.getOrDefault(emptyList())

/**
 * Whether [peer] sits on one of the networks this device is itself on, decided by masking both
 * addresses with the interface's own prefix length rather than by testing them against the textbook
 * private ranges.
 *
 * That distinction is the whole point. "Is it a private address?" answers a different, much weaker
 * question: every home router hands out 192.168.x.x, so a private address only says the peer is on *a*
 * local network somewhere, not on *this* one. Reaching it would still require routing, which is
 * precisely what this feature must never do with a backup that carries repository passwords and a
 * GitHub token. Same subnet as one of our own interfaces means the two devices are genuinely on the
 * same link.
 */
fun isOnSameLocalNetwork(peer: InetAddress): Boolean {
    if (peer !is Inet4Address) return false
    // A public address can never be on a local link, whatever the masking says, and refusing it up
    // front means a misread prefix length can never open one up.
    if (!peer.isSiteLocalAddress && !peer.isLinkLocalAddress) return false
    return usableLocalAddresses().any { local -> local.sharesNetworkWith(peer) }
}

private fun LocalInterfaceAddress.sharesNetworkWith(peer: Inet4Address): Boolean {
    // A prefix outside 1..32 is meaningless for IPv4; treating it as "no match" is the safe reading,
    // since the alternative (a zero-length mask) would match every address in existence.
    if (prefixLength !in 1..32) return false
    val mask = (-1L shl (32 - prefixLength)).toInt()
    return address.toIntAddress() and mask == peer.toIntAddress() and mask
}

private fun Inet4Address.toIntAddress(): Int = this.address.fold(0) { acc, byte ->
    (acc shl 8) or (byte.toInt() and 0xFF)
}

/**
 * Every IPv4 address this device holds on a real local network: up, not loopback, not a tunnel or
 * mobile interface (see [EXCLUDED_INTERFACE_PREFIXES]), and site- or link-local.
 *
 * Never throws: enumerating interfaces can fail outright on some devices, and a transfer that simply
 * reports "no local network" is a far better outcome there than a crash inside Settings.
 */
private fun usableLocalAddresses(): List<LocalInterfaceAddress> = runCatching {
    NetworkInterface.getNetworkInterfaces()
        ?.toList()
        .orEmpty()
        .filter { networkInterface ->
            runCatching { networkInterface.isUp && !networkInterface.isLoopback }.getOrDefault(false) &&
                EXCLUDED_INTERFACE_PREFIXES.none { networkInterface.name.startsWith(it) }
        }
        .flatMap { networkInterface ->
            networkInterface.interfaceAddresses.mapNotNull { interfaceAddress ->
                val address = interfaceAddress.address as? Inet4Address ?: return@mapNotNull null
                if (!address.isSiteLocalAddress && !address.isLinkLocalAddress) return@mapNotNull null
                LocalInterfaceAddress(
                    address = address,
                    prefixLength = interfaceAddress.networkPrefixLength,
                    interfaceName = networkInterface.name,
                )
            }
        }
}.getOrDefault(emptyList())

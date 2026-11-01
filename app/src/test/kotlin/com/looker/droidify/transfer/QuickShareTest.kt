package com.looker.droidify.transfer

import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.PrereleaseMode
import com.looker.droidify.external.SourceProvider
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickShareTest {

    private val secret = ByteArray(32) { 7 }
    private val nonce = ByteArray(16) { 1 }
    private val phone = "0123456789abcdef0123456789abcdef"
    private val tv = "fedcba9876543210fedcba9876543210"

    @Test
    fun `the opening tag names the device it is for and the one it is from`() {
        val tag = quickHelloTag(secret, nonce, receiverId = tv, senderId = phone)
        assertContentEquals(tag, quickHelloTag(secret, nonce, receiverId = tv, senderId = phone))
        // Another device paired with the same phone turns it away: the tag was not made for it.
        assertNotEquals(tag.toList(), quickHelloTag(secret, nonce, receiverId = phone, senderId = tv).toList())
        assertNotEquals(tag.toList(), quickHelloTag(ByteArray(32) { 8 }, nonce, tv, phone).toList())
        assertNotEquals(tag.toList(), quickHelloTag(secret, ByteArray(16) { 2 }, tv, phone).toList())
    }

    @Test
    fun `values cannot move between transcript fields`() {
        val a = quickTranscript(byteArrayOf(1, 2), byteArrayOf(3), byteArrayOf(4), byteArrayOf(5), phone, tv)
        val b = quickTranscript(byteArrayOf(1), byteArrayOf(2, 3), byteArrayOf(4), byteArrayOf(5), phone, tv)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `the session key needs the pairing secret`() {
        val transcript = quickTranscript(nonce, nonce, byteArrayOf(4), byteArrayOf(5), phone, tv)
        val shared = ByteArray(32) { 9 }
        assertNotEquals(
            quickSessionKey(secret, shared, transcript).toList(),
            quickSessionKey(ByteArray(32) { 8 }, shared, transcript).toList(),
        )
    }

    private val app = ExternalApp(
        provider = SourceProvider.GITHUB,
        owner = "example",
        repo = "app",
        label = "Example",
        prereleases = PrereleaseMode.BOTH,
        apkFilter = "arm64",
        installedTag = "v1.0",
        packageName = "org.example.app",
    )

    @Test
    fun `a source survives the trip with how it is followed, not this device's copy of it`() {
        val received = decodeQuickShare(encodeQuickShare(listOf(app)))!!.single()
        assertEquals(app.key, received.key)
        assertEquals("Example", received.label)
        assertEquals(PrereleaseMode.BOTH, received.prereleases)
        assertEquals("arm64", received.apkFilter)
        assertNull(received.installedTag)
        assertNull(received.packageName)
    }

    @Test
    fun `GitLab groups and self-hosted instances are kept`() {
        val sources = listOf(
            app.copy(provider = SourceProvider.GITLAB, owner = "group/sub"),
            app.copy(provider = SourceProvider.CODEBERG, host = "git.example.org"),
            app.copy(provider = SourceProvider.GITLAB, host = "gitlab.example.org:8443", owner = "team/apps"),
            app.copy(variant = "photos-desktop", label = "Photos \uD83D\uDCF7"),
        )
        assertEquals(sources.map { it.key }, decodeQuickShare(encodeQuickShare(sources))!!.map { it.key })
    }

    @Test
    fun `a source that does not say what it points at is dropped`() {
        val sources = listOf(
            // A public host passed off as a self-hosted one, or under a provider never self-hosted here.
            app.copy(provider = SourceProvider.CODEBERG, host = "github.com"),
            app.copy(provider = SourceProvider.CODEBERG, host = "www.github.com"),
            app.copy(provider = SourceProvider.GITHUB, host = "git.example.org"),
            // Addresses and names that only resolve on a local network.
            app.copy(provider = SourceProvider.CODEBERG, host = "192.168.1.10"),
            app.copy(provider = SourceProvider.CODEBERG, host = "router"),
            app.copy(provider = SourceProvider.CODEBERG, host = "[::1]"),
            app.copy(provider = SourceProvider.CODEBERG, host = "git.example.org:0"),
            app.copy(provider = SourceProvider.CODEBERG, host = "git.example.org:08443"),
            // Not a host name at all.
            app.copy(provider = SourceProvider.CODEBERG, host = "evil host/../"),
            // Paths outside what providers allow, or nested where only GitLab nests.
            app.copy(owner = "../x"),
            app.copy(owner = "group/sub"),
            app.copy(provider = SourceProvider.GITLAB, owner = "group//sub"),
            app.copy(repo = "app?x=1"),
            app.copy(owner = ""),
            app.copy(variant = "Not A Variant"),
            // Text that would read as something other than what it holds.
            app.copy(label = "Example\u0000"),
            app.copy(label = "Exa\u202Eelpm"),
            app.copy(label = "Exam\u200Bple"),
            app.copy(apkFilter = "arm64\u2028"),
            app.copy(repo = "x".repeat(500)),
        )
        assertEquals(emptyList(), decodeQuickShare(encodeQuickShare(sources)))
    }

    @Test
    fun `hidden characters are found whole and can be taken out`() {
        assertTrue("Living room\u200E TV".hasHiddenCharacters())
        assertTrue("TV\uDB40\uDC01".hasHiddenCharacters())
        assertEquals("Living room TV", "Living room\u200E TV".withoutHiddenCharacters())
        assertEquals("Salon \uD83D\uDCFA", "Salon \uD83D\uDCFA".withoutHiddenCharacters())
    }

    @Test
    fun `what is not a quick share is refused`() {
        assertNull(decodeQuickShare("not json".toByteArray()))
        assertNull(decodeQuickShare(ByteArray(MAX_QUICK_SHARE_BYTES + 1)))
        assertTrue(decodeQuickShare("""{"sources":[]}""".toByteArray())!!.isEmpty())
    }

    @Test
    fun `device identifiers are checked before being trusted`() {
        assertTrue(phone.isDeviceId())
        assertTrue(!"ABCDEF".isDeviceId())
        assertTrue(!"g123456789abcdef0123456789abcdef".isDeviceId())
    }

    private val address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20))
    private val other = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 21))

    @Test
    fun `an address whose connections keep going nowhere is turned away for a while`() {
        var now = 0L
        val guard = PeerGuard { now }
        repeat(PeerGuard.MAX_FAILURES_PER_ADDRESS) {
            assertTrue(guard.admit(address))
            guard.release(address, failed = true)
        }
        assertFalse(guard.admit(address))
        assertTrue(guard.admit(other))
        guard.release(other, failed = false)
        now += PeerGuard.COOLDOWN_MS
        assertTrue(guard.admit(address))
    }

    @Test
    fun `an exchange that went through clears an address's failures`() {
        val guard = PeerGuard { 0L }
        repeat(PeerGuard.MAX_FAILURES_PER_ADDRESS - 1) {
            guard.admit(address)
            guard.release(address, failed = true)
        }
        guard.admit(address)
        guard.release(address, failed = false)
        guard.admit(address)
        guard.release(address, failed = true)
        assertTrue(guard.admit(address))
    }

    @Test
    fun `no address holds every place, and the places are bounded`() {
        val guard = PeerGuard { 0L }
        repeat(PeerGuard.MAX_PEERS_PER_ADDRESS) { assertTrue(guard.admit(address)) }
        assertFalse(guard.admit(address))
        repeat(PeerGuard.MAX_CONCURRENT_PEERS - PeerGuard.MAX_PEERS_PER_ADDRESS) {
            assertTrue(guard.admit(InetAddress.getByAddress(byteArrayOf(10, 0, 0, it.toByte()))))
        }
        assertFalse(guard.admit(other))
        guard.release(address, failed = false)
        assertTrue(guard.admit(other))
    }
}

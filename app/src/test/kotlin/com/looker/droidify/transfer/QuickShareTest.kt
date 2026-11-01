package com.looker.droidify.transfer

import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.PrereleaseMode
import com.looker.droidify.external.SourceProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
    fun `a GitLab group and a self-hosted instance are kept`() {
        val group = app.copy(provider = SourceProvider.GITLAB, owner = "group/sub")
        val gitea = app.copy(provider = SourceProvider.CODEBERG, host = "git.example.org")
        val received = decodeQuickShare(encodeQuickShare(listOf(group, gitea)))!!
        assertEquals(listOf(group.key, gitea.key), received.map { it.key })
    }

    @Test
    fun `a source that does not say what it points at is dropped`() {
        val sources = listOf(
            // A public host passed off as a self-hosted one, or under the wrong provider.
            app.copy(host = "github.com"),
            app.copy(provider = SourceProvider.GITHUB, host = "git.example.org"),
            // Not a host name at all.
            app.copy(provider = SourceProvider.CODEBERG, host = "evil host/../"),
            // A name that would read as more than a name.
            app.copy(label = "Example\u0000"),
            app.copy(repo = "x".repeat(500)),
            app.copy(owner = ""),
        )
        assertEquals(emptyList(), decodeQuickShare(encodeQuickShare(sources)))
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
}

package com.looker.droidify.transfer

import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.PrereleaseMode
import com.looker.droidify.external.SourceProvider
import com.looker.droidify.external.publicHost
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Sources sent in one quick share at most. */
private const val MAX_SOURCES = 50

/** Longest text field accepted from another device: far beyond any real owner, repository, name or
 *  filter, and a bound on what a paired device can make this one store and show. */
private const val MAX_FIELD_LENGTH = 200

/** One segment of an owner or repository path, in the characters every provider followed here allows. */
private val PATH_SEGMENT = Regex("[A-Za-z0-9._-]+")

/** One label of a host name as DNS allows it. */
private val HOST_LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

/** What [variantOf] makes. */
private val VARIANT = Regex("[a-z0-9-]*")

/**
 * One external source as a quick share carries it: what identifies the project and how it is
 * followed, and nothing about this device's own copy of it (installed version, signers, cached icon),
 * which means nothing on another device and is worked out there again once the source is added.
 */
@Serializable
internal class SharedSource(
    val provider: SourceProvider,
    val host: String = "",
    val owner: String,
    val repo: String,
    val variant: String = "",
    val label: String,
    val nameOverridden: Boolean = false,
    val prereleases: PrereleaseMode = PrereleaseMode.HIDDEN,
    val apkFilter: String? = null,
    val versionIncludeFilter: String? = null,
    val versionExcludeFilter: String? = null,
)

@Serializable
private class QuickSharePayload(val sources: List<SharedSource>)

private val JSON = Json { ignoreUnknownKeys = true }

/** [apps] as the bytes a quick share carries. */
fun encodeQuickShare(apps: List<ExternalApp>): ByteArray = JSON.encodeToString(
    QuickSharePayload.serializer(),
    QuickSharePayload(
        apps.take(MAX_SOURCES).map { app ->
            SharedSource(
                provider = app.provider,
                host = app.host,
                owner = app.owner,
                repo = app.repo,
                variant = app.variant,
                label = app.label,
                nameOverridden = app.nameOverridden,
                prereleases = app.prereleases,
                apkFilter = app.apkFilter,
                versionIncludeFilter = app.versionIncludeFilter,
                versionExcludeFilter = app.versionExcludeFilter,
            )
        },
    ),
).toByteArray(Charsets.UTF_8)

/**
 * The sources a quick share carries, as new sources to add, or null when the bytes are not a quick
 * share at all.
 *
 * Everything here arrived from another device, so each source is checked before it is offered, and
 * dropped when any part of it says more than it seems to: a path outside the characters providers
 * allow, a host that is an address or one of the public providers in disguise, or text carrying
 * characters that do not show (bidirectional overrides, zero-width marks) and could make one source
 * read as another. What survives still waits for the user to accept it.
 */
fun decodeQuickShare(bytes: ByteArray): List<ExternalApp>? {
    if (bytes.size > MAX_QUICK_SHARE_BYTES) return null
    val payload = runCatching {
        JSON.decodeFromString(QuickSharePayload.serializer(), bytes.toString(Charsets.UTF_8))
    }.getOrNull() ?: return null
    return payload.sources.take(MAX_SOURCES).mapNotNull { it.toExternalApp() }.distinctBy { it.key }
}

private fun SharedSource.toExternalApp(): ExternalApp? {
    val texts = listOfNotNull(host, owner, repo, variant, label, apkFilter, versionIncludeFilter, versionExcludeFilter)
    if (texts.any { it.length > MAX_FIELD_LENGTH || it.hasHiddenCharacters() }) return null
    if (label.isBlank() || !VARIANT.matches(variant)) return null
    if (!isRepoPath(owner, nested = provider == SourceProvider.GITLAB) || !isRepoPath(repo, nested = false)) return null
    // A self-hosted instance is only ever GitLab or Gitea/Forgejo, which this app files under Codeberg.
    if (host.isNotEmpty() && (provider == SourceProvider.GITHUB || !isSelfHostedHost(host))) return null
    return ExternalApp(
        provider = provider,
        host = host,
        owner = owner,
        repo = repo,
        variant = variant,
        label = label.trim(),
        nameOverridden = nameOverridden,
        prereleases = prereleases,
        apkFilter = apkFilter?.takeIf { it.isNotBlank() },
        versionIncludeFilter = versionIncludeFilter?.takeIf { it.isNotBlank() },
        versionExcludeFilter = versionExcludeFilter?.takeIf { it.isNotBlank() },
    )
}

/** An owner or repository path: one segment, or several for a GitLab group ([nested]). */
private fun isRepoPath(path: String, nested: Boolean): Boolean {
    val segments = if (nested) path.split('/') else listOf(path)
    return segments.all { PATH_SEGMENT.matches(it) && it != "." && it != ".." }
}

/**
 * A host name a self-hosted instance could have: labels DNS allows, at least two of them, the last
 * with a letter in it (so never an IP address), an optional port, and none of the public providers
 * (which would make it read as one while pointing elsewhere, or duplicate one under another key).
 */
private fun isSelfHostedHost(host: String): Boolean {
    val name = host.substringBefore(':')
    val port = host.substringAfter(':', "")
    if (port.isNotEmpty() && port.toIntOrNull()?.takeIf { it in 1..65535 }?.toString() != port) return false
    val labels = name.split('.')
    if (name.length > 253 || labels.size < 2 || !labels.all { HOST_LABEL.matches(it) }) return false
    if (labels.last().none { it in 'a'..'z' }) return false
    return SourceProvider.entries.none { publicHost(it) in name }
}

/** Whether this text holds a character that does not show for what it is: a control character, a
 *  line break, or a format character such as a bidirectional override or a zero-width mark. */
internal fun String.hasHiddenCharacters(): Boolean {
    forEachCodePoint { if (it.isHiddenCodePoint()) return true }
    return false
}

/** This text without the characters [hasHiddenCharacters] looks for. */
internal fun String.withoutHiddenCharacters(): String = buildString {
    this@withoutHiddenCharacters.forEachCodePoint { if (!it.isHiddenCodePoint()) appendCodePoint(it) }
}

/** By code point rather than by char, so a character outside the basic plane is judged whole.
 *  String.codePoints() would do, but needs Android 7. */
private inline fun String.forEachCodePoint(action: (Int) -> Unit) {
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        action(codePoint)
        index += Character.charCount(codePoint)
    }
}

private fun Int.isHiddenCodePoint(): Boolean = Character.isISOControl(this) ||
    when (Character.getType(this).toByte()) {
        Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SURROGATE -> true
        else -> false
    }

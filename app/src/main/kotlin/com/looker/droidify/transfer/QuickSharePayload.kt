package com.looker.droidify.transfer

import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.PrereleaseMode
import com.looker.droidify.external.SourceProvider
import com.looker.droidify.external.parseExternalSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Sources sent in one quick share at most. */
private const val MAX_SOURCES = 50

/** Longest text field accepted from another device: far beyond any real owner, repository, name or
 *  filter, and a bound on what a paired device can make this one store and show. */
private const val MAX_FIELD_LENGTH = 200

/** A host name as DNS allows it: letters, digits, dots and hyphens, with an optional port. */
private val HOST_NAME = Regex("[a-z0-9.-]{1,253}(:[0-9]{1,5})?")

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
 * Everything here arrived from another device, paired or not, so each source is checked before it is
 * offered: a source that does not survive the same address parsing a pasted link goes through, or
 * whose fields are out of bounds, is dropped rather than added. What survives still waits for the
 * user to accept it.
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
    if (texts.any { it.length > MAX_FIELD_LENGTH || it.any(Char::isISOControl) }) return null
    if (owner.isBlank() || repo.isBlank() || label.isBlank()) return null
    if (host.isNotEmpty() && !HOST_NAME.matches(host)) return null
    val app = ExternalApp(
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
    // Read back through the parser a pasted address goes through, so a source arrives only if it is
    // one this device could have added by hand, pointing at the project it says it does. Written out
    // plainly rather than taken from webUrl, which escapes the path (a GitLab group's slash among it).
    val ref = parseExternalSource("https://${app.effectiveHost}/$owner/$repo") ?: return null
    if (ref.owner != owner || ref.repo != repo) return null
    val consistent = if (host.isEmpty()) {
        ref.provider == provider
    } else {
        // A self-hosted instance is never one of the public hosts, and only Gitea/Forgejo, which this
        // app files under Codeberg, is followed on one.
        ref.provider == null && ref.host == host && provider == SourceProvider.CODEBERG
    }
    return app.takeIf { consistent }
}

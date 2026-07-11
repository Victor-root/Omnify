package com.looker.droidify.external

import kotlinx.serialization.Serializable

/**
 * A code-hosting platform Droidify can track release APKs from (Obtainium-style). Each provider
 * exposes a slightly different releases REST API; [ExternalApi] normalises them to a common
 * [Release].
 */
@Serializable
enum class SourceProvider(val label: String) {
    GITHUB("GitHub"),
    GITLAB("GitLab"),
    CODEBERG("Codeberg"),
}

/** A parsed reference to a project: enough to build API + web URLs (after the host is known). */
data class ExternalSourceRef(
    /** Null when the host isn't a known public provider — the caller probes the instance
     *  (self-hosted Gitea/Forgejo) to resolve the actual [SourceProvider]. */
    val provider: SourceProvider?,
    /** "" for a provider's public host; the instance host (e.g. "git.example.org") otherwise. */
    val host: String,
    val owner: String,
    val repo: String,
)

/** A parsed reference to a whole account (a user or org), not a single repo: e.g.
 *  `github.com/owner`. Used for the "all my apps" account source. */
data class ExternalAccountRef(
    val provider: SourceProvider?,
    val host: String,
    val owner: String,
)

/**
 * Parses an account URL (owner only, no repo): `github.com/owner`, `https://github.com/owner`,
 * `codeberg.org/owner`, `gitlab.com/group`, a self-hosted `git.example.org/owner`, or a bare `owner`
 * (assumed GitHub). Returns null when no owner can be found. Only the first path segment is taken as the
 * owner, so this is meant as a fallback after [parseExternalSource] (which handles owner/repo) fails.
 */
fun parseAccountSource(input: String): ExternalAccountRef? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    val withoutScheme = trimmed.substringAfter("://", trimmed)
    val firstSegment = withoutScheme.substringBefore('/').lowercase()
    val hasHost = firstSegment.contains('.')

    val provider: SourceProvider? = when {
        "github.com" in firstSegment -> SourceProvider.GITHUB
        "gitlab.com" in firstSegment -> SourceProvider.GITLAB
        "codeberg.org" in firstSegment -> SourceProvider.CODEBERG
        !hasHost -> SourceProvider.GITHUB
        else -> null
    }
    val host = if (provider == null) firstSegment else ""

    var path = if (hasHost) withoutScheme.substringAfter('/', "") else withoutScheme
    path = path
        .removePrefix("users/")
        .removePrefix("orgs/")
        .substringBefore("/-/")

    val owner = path.split('/').firstOrNull { it.isNotBlank() }?.removeSuffix(".git")
    if (owner.isNullOrBlank()) return null
    return ExternalAccountRef(provider, host, owner)
}

/**
 * Detects the provider and `owner/repo` from the many URL forms a user might paste:
 *  - `github.com/owner/repo`, `https://github.com/owner/repo/releases`, `owner/repo` (assumed GitHub)
 *  - `gitlab.com/group/subgroup/repo`, `gitlab.com/owner/repo/-/releases`
 *  - `codeberg.org/owner/repo`
 *
 * For GitLab the project path can be nested (groups), so everything before the last segment is kept
 * as [ExternalSourceRef.owner]. Returns null when no `owner/repo` pair can be found or the host is
 * not a supported provider.
 */
fun parseExternalSource(input: String): ExternalSourceRef? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    val withoutScheme = trimmed.substringAfter("://", trimmed)
    val firstSegment = withoutScheme.substringBefore('/').lowercase()
    val hasHost = firstSegment.contains('.')

    val provider: SourceProvider? = when {
        "github.com" in firstSegment -> SourceProvider.GITHUB
        "gitlab.com" in firstSegment -> SourceProvider.GITLAB
        "codeberg.org" in firstSegment -> SourceProvider.CODEBERG
        // A bare "owner/repo" with no host is assumed to be GitHub (the most common case).
        !hasHost -> SourceProvider.GITHUB
        // Any other host is a possible self-hosted instance; the caller probes whether it's
        // Gitea/Forgejo. The host is kept so the probe and later API calls can reach it.
        else -> null
    }
    val host = if (provider == null) firstSegment else ""

    // Drop the host (when present) and any API prefixes, then everything from GitLab's "/-/" marker
    // (e.g. ".../owner/repo/-/releases") which is never part of the project path.
    var path = if (hasHost) withoutScheme.substringAfter('/', "") else withoutScheme
    path = path
        .removePrefix("repos/")
        .removePrefix("api/v4/projects/")
        .removePrefix("api/v1/repos/")
        .substringBefore("/-/")

    val segments = path.split('/').filter { it.isNotBlank() }
    if (segments.size < 2) return null

    // gitlab.com nests groups (owner can be "group/subgroup"); GitHub, Codeberg and self-hosted
    // Gitea/Forgejo use a flat owner/repo, so the first two segments are taken.
    return if (provider == SourceProvider.GITLAB) {
        val repo = segments.last().removeSuffix(".git")
        val owner = segments.dropLast(1).joinToString("/")
        if (owner.isBlank() || repo.isBlank()) null
        else ExternalSourceRef(provider, host, owner, repo)
    } else {
        val owner = segments[0]
        val repo = segments[1].removeSuffix(".git")
        if (owner.isBlank() || repo.isBlank()) null
        else ExternalSourceRef(provider, host, owner, repo)
    }
}

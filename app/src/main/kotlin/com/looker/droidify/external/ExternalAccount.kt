package com.looker.droidify.external

import kotlinx.serialization.Serializable

/**
 * A whole code-hosting account (a user or organization) tracked as one source: Omnify discovers every
 * repo of [owner] that publishes an installable APK release and tracks each as its own [ExternalApp]
 * (tagged with this account's [key]). The account is shown as a single row in the sources list, while
 * its apps appear individually on the External tab.
 *
 * Persisted as JSON alongside the single-repo sources (see [ExternalAppRepository]).
 */
@Serializable
data class ExternalAccount(
    val provider: SourceProvider = SourceProvider.GITHUB,
    val owner: String,
    /** The instance host for a self-hosted Gitea/Forgejo/GitLab; empty for the provider's public host. */
    val host: String = "",
    /** Display name; defaults to the owner. */
    val label: String = owner,
    /** Optional free-text description shown on the account's Info tab (e.g. who the maintainer is).
     *  Empty for an account added by URL; only the built-in Omnify account seeds one today. */
    val description: String = "",
    /** Active state. Disabling the account disables all of its discovered apps too. */
    val enabled: Boolean = true,
    /** Whether forked repos are included in the discovery. Off by default (forks are usually noise), but
     *  some accounts publish their apps as forks of upstream projects, so it's a per-account choice.
     *  No effect on GitLab, whose project list doesn't flag forks. */
    val includeForks: Boolean = false,
    /** Epoch millis of the last repo-list scan (0 = never), driving the once-a-day auto rescan that
     *  picks up newly published apps. */
    val lastScan: Long = 0,
    /** True for an account Omnify itself ships pre-seeded as a suggestion (see MainComposeActivity),
     *  shown in the "Omnify's picks" section on the repositories screen instead of the regular sources
     *  list — mirrors [com.looker.droidify.external.ExternalApp.curated]. */
    val curated: Boolean = false,
) {
    val effectiveHost: String
        get() = host.ifEmpty {
            when (provider) {
                SourceProvider.GITHUB -> "github.com"
                SourceProvider.GITLAB -> "gitlab.com"
                SourceProvider.CODEBERG -> "codeberg.org"
            }
        }

    /** Stable identity, matching [ExternalApp.accountKey] of its discovered apps. */
    val key: String
        get() = if (host.isEmpty()) "${provider.name}/$owner" else "${provider.name}/$host/$owner"

    /** Origin shown in the UI: the provider name for a public host, else the instance host. */
    val sourceLabel: String get() = if (host.isEmpty()) provider.label else host

    val webUrl: String get() = "https://$effectiveHost/$owner"

    /** Account avatar where one is stably addressable by name (GitHub); null otherwise (falls back to a
     *  letter monogram in the UI). */
    val iconUrl: String?
        get() = when {
            provider == SourceProvider.GITHUB && host.isEmpty() -> "https://github.com/$owner.png"
            else -> null
        }

    companion object {
        /** Key of the built-in Omnify source (the developer's GitHub account), seeded on first run and
         *  shown with the app's own logo. */
        const val OMNIFY_KEY = "GITHUB/Victor-root"
    }
}

package com.looker.droidify.compose.repoList

/**
 * Hardcoded icon URLs for the bundled default repositories, keyed by repo address (without a trailing
 * slash). Most default repos ship disabled, so they're never synced and we hold no icon for them; this
 * gives the repositories list a real, recognizable logo before the user enables anything.
 *
 * Each URL is the icon the repo itself declares in its index (`<address>/icons/<name>`), confirmed to
 * be a real logo. Repos whose declared icon is only the generic F-Droid placeholder (several share the
 * exact same default file) or a QR code of their address (a pure two-colour image, useless as a logo)
 * are deliberately left out: they fall back to the letter monogram, which is more distinctive. Keys are
 * matched against [com.looker.droidify.data.model.Repo]'s address with any trailing slash trimmed.
 */
internal val DEFAULT_REPO_ICONS: Map<String, String> = mapOf(
    "https://f-droid.org/repo" to
        "https://f-droid.org/repo/icons/icon.png",
    // The archive is the same project as F-Droid, so it shares the F-Droid logo.
    "https://f-droid.org/archive" to
        "https://f-droid.org/repo/icons/icon.png",
    "https://guardianproject.info/fdroid/repo" to
        "https://guardianproject.info/fdroid/repo/icons/guardianproject.png",
    "https://guardianproject.info/fdroid/archive" to
        "https://guardianproject.info/fdroid/archive/icons/guardianproject.png",
    "https://apt.izzysoft.de/fdroid/repo" to
        "https://apt.izzysoft.de/fdroid/repo/icons/izzy-on-droid.png",
    "https://microg.org/fdroid/repo" to
        "https://microg.org/fdroid/repo/icons/fdroid-icon.png",
    "https://repo.netsyms.com/fdroid/repo" to
        "https://repo.netsyms.com/fdroid/repo/icons/netsyms-48.png",
    "https://molly.im/fdroid/foss/fdroid/repo" to
        "https://molly.im/fdroid/foss/fdroid/repo/icons/molly-icon.png",
    "https://archive.newpipe.net/fdroid/repo" to
        "https://archive.newpipe.net/fdroid/repo/icons/newpipe.png",
    "https://www.collaboraoffice.com/downloads/fdroid/repo" to
        "https://www.collaboraoffice.com/downloads/fdroid/repo/icons/ic_launcher_brand.png",
    "https://cdn.kde.org/android/fdroid/repo" to
        "https://cdn.kde.org/android/fdroid/repo/icons/kde.png",
    "https://cdn.kde.org/android/stable-releases/fdroid/repo" to
        "https://cdn.kde.org/android/stable-releases/fdroid/repo/icons/kde.png",
    "https://calyxos.gitlab.io/calyx-fdroid-repo/fdroid/repo" to
        "https://calyxos.gitlab.io/calyx-fdroid-repo/fdroid/repo/icons/fdroid-icon.png",
    "https://store.nethunter.com/repo" to
        "https://store.nethunter.com/repo/icons/nethunter-git-logo.png",
    "https://mobileapp.bitwarden.com/fdroid/repo" to
        "https://mobileapp.bitwarden.com/fdroid/repo/icons/icon.png",
    "https://briarproject.org/fdroid/repo" to
        "https://briarproject.org/fdroid/repo/icons/briar-icon.png",
    "https://releases.threema.ch/fdroid/repo" to
        "https://releases.threema.ch/fdroid/repo/icons/repo-icon.png",
    "https://fdroid.getsession.org/fdroid/repo" to
        "https://fdroid.getsession.org/fdroid/repo/icons/icon.png",
    "https://fdroid.typeblog.net" to
        "https://fdroid.typeblog.net/icons/fdroid-icon.png",
    "https://zimbelstern.eu/fdroid/repo" to
        "https://zimbelstern.eu/fdroid/repo/icons/icon.png",
    "https://app.simplex.chat/fdroid/repo" to
        "https://app.simplex.chat/fdroid/repo/icons/1.png",
    "https://f-droid.monerujo.io/fdroid/repo" to
        "https://f-droid.monerujo.io/fdroid/repo/icons/fdroid-icon.png",
    "https://fdroid.cakelabs.com/fdroid/repo" to
        "https://fdroid.cakelabs.com/fdroid/repo/icons/icon.png",
    "https://app.futo.org/fdroid/repo" to
        "https://app.futo.org/fdroid/repo/icons/FUTO.png",
    "https://fdroid.mm20.de/repo" to
        "https://fdroid.mm20.de/repo/icons/icon.png",
    "https://gh.artemchep.com/keyguard-repo-fdroid/repo" to
        "https://gh.artemchep.com/keyguard-repo-fdroid/repo/icons/icon.png",
    "https://fdroid.i2pd.xyz/fdroid/repo" to
        "https://fdroid.i2pd.xyz/fdroid/repo/icons/purplei2p.png",
    "https://fdroid.ironfoxoss.org/fdroid/repo" to
        "https://fdroid.ironfoxoss.org/fdroid/repo/icons/ironfox.png",
    // These repos publish a QR code (or placeholder) as their repo icon, but each is a single-app repo,
    // so we use that app's real icon instead. Fcitx5's own repo only holds plugins, so its logo comes
    // from the main app on F-Droid. (Wind Project and Patched Apps are genuine multi-app collections
    // with no single logo, so they keep the letter monogram.)
    "https://fdroid.fedilab.app/repo" to
        "https://fdroid.fedilab.app/repo/fr.gouv.etalab.mastodon/en-US/icon_2Jq-Bi5vXhNbP7Gd4VO4p77Ws9PviSLtxIt4aKMrAL0=.png",
    "https://fdroid.cakelabs.com/fdroid/repo" to
        "https://fdroid.cakelabs.com/fdroid/repo/com.cakewallet.cake_wallet/en-US/icon_bE9jI564FlPPouyY_mULU-3YXWTUqi1SqxxXjbBRCsE=.png",
    "https://breezy-weather.github.io/fdroid-repo/fdroid/repo" to
        "https://breezy-weather.github.io/fdroid-repo/fdroid/repo/org.breezyweather/en-US/icon_9s6OWodFUYb8-7w_fcaXpGLD1cw1YKEVneV4H1xpToI=.png",
    "https://f5a.torus.icu/fdroid/repo" to
        "https://f-droid.org/repo/org.fcitx.fcitx5.android/en-US/icon_oJHkpx5GsjyrG5_nxTfEs2FmP6g7_hmnbE6rKQjLRoI=.png",
    "https://brave-browser-apk-release.s3.brave.com/fdroid/repo" to
        "https://brave-browser-apk-release.s3.brave.com/fdroid/repo/icons/icon.png",
    "https://brave-browser-apk-beta.s3.brave.com/fdroid/repo" to
        "https://brave-browser-apk-beta.s3.brave.com/fdroid/repo/icons/icon.png",
    "https://brave-browser-apk-nightly.s3.brave.com/fdroid/repo" to
        "https://brave-browser-apk-nightly.s3.brave.com/fdroid/repo/icons/icon.png",
)

/** The hardcoded logo URL for a repo [address] (trailing slash ignored), or null when none is known. */
internal fun defaultRepoIcon(address: String): String? =
    DEFAULT_REPO_ICONS[address.trimEnd('/')]

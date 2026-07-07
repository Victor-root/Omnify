package com.looker.droidify.compose.repoList

import com.looker.droidify.R

/**
 * Hardcoded icon URLs for the bundled default repositories, keyed by repo address (without a trailing
 * slash). Most default repos ship disabled, so they're never synced and we hold no icon for them; this
 * gives the repositories list a real, recognizable logo before the user enables anything.
 *
 * Each URL is a real logo, confirmed to be an actual image: usually the icon the repo declares in its
 * index (`<address>/icons/<name>`), or, when that's only the generic F-Droid placeholder or a QR code of
 * the address, the repo's flagship app icon instead. Repos that ship neither (no usable repo icon and no
 * single representative app, e.g. multi-app collections) are left out and fall back to the letter
 * monogram. Keys are matched against [com.looker.droidify.data.model.Repo]'s address, trailing slash
 * trimmed.
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
    // These repos publish a QR code or the generic placeholder as their repo icon, but their flagship
    // app has a real icon, so we use that instead. Fcitx5's own repo only holds plugins, so its logo
    // comes from the main app on F-Droid. (Wind Project and Patched Apps are multi-app collections,
    // handled by DEFAULT_REPO_ICON_RES below.)
    "https://fdroid.fedilab.app/repo" to
        "https://fdroid.fedilab.app/repo/fr.gouv.etalab.mastodon/en-US/icon_2Jq-Bi5vXhNbP7Gd4VO4p77Ws9PviSLtxIt4aKMrAL0=.png",
    "https://fdroid.cakelabs.com/fdroid/repo" to
        "https://fdroid.cakelabs.com/fdroid/repo/com.cakewallet.cake_wallet/en-US/icon_bE9jI564FlPPouyY_mULU-3YXWTUqi1SqxxXjbBRCsE=.png",
    "https://breezy-weather.github.io/fdroid-repo/fdroid/repo" to
        "https://breezy-weather.github.io/fdroid-repo/fdroid/repo/org.breezyweather/en-US/icon_9s6OWodFUYb8-7w_fcaXpGLD1cw1YKEVneV4H1xpToI=.png",
    "https://f5a.torus.icu/fdroid/repo" to
        "https://f-droid.org/repo/org.fcitx.fcitx5.android/en-US/icon_oJHkpx5GsjyrG5_nxTfEs2FmP6g7_hmnbE6rKQjLRoI=.png",
    "https://static.cryptomator.org/android/fdroid/repo" to
        "https://static.cryptomator.org/android/fdroid/repo/org.cryptomator/en-US/icon_7xJFlQlRtgL84gUfd2p_havFrUXO36Gb7Y00E21HhaA=.png",
    // SpiritCroc's flagship app is SchildiChat, so the repo uses its icon.
    "https://s2.spiritcroc.de/fdroid/repo" to
        "https://s2.spiritcroc.de/fdroid/repo/chat.schildi.android/en-US/icon_F6cFvN-V4ZlfBHqQDDJ2ZTkxx24CwjkAN5gHZDFr_e8=.png",
    "https://s2.spiritcroc.de/testing/fdroid/repo" to
        "https://s2.spiritcroc.de/testing/fdroid/repo/chat.schildi.next/en-US/icon_F6cFvN-V4ZlfBHqQDDJ2ZTkxx24CwjkAN5gHZDFr_e8=.png",
    // Total Commander serves only the legacy v1 index, whose icons live under icons-640/.
    "https://raw.githubusercontent.com/chrisgch/tca/master/fdroid/repo" to
        "https://raw.githubusercontent.com/chrisgch/tca/master/fdroid/repo/icons-640/com.ghisler.android.TotalCommander.png",
    // These ship no usable icon in their index, so the logo comes from the project itself: Signal's
    // logo for TwinHelix's Signal-FOSS, and the NanoLX brand icon for NanoDroid.
    "https://fdroid.twinhelix.com/fdroid/repo" to
        "https://upload.wikimedia.org/wikipedia/commons/thumb/8/8d/Signal-Logo.svg/330px-Signal-Logo.svg.png",
    "https://nanolx.org/fdroid/repo" to
        "https://nanolx.org/wp-content/uploads/cropped-cropped-Nanolx3-small-1-192x192.png",
    // Cromite's F-Droid repo didn't serve its index to a non-browser client; its app icon is published
    // on the project site.
    "https://www.cromite.org/fdroid/repo" to
        "https://www.cromite.org/app_icon.png",
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

/**
 * Bundled drawable icons for repos that are multi-app collections with no single brand logo (and whose
 * declared icon is a QR code): a generic "multiple apps" glyph reads better than a single letter. Takes
 * priority over the (QR) synced icon. Keyed by repo address, trailing slash trimmed.
 */
internal val DEFAULT_REPO_ICON_RES: Map<String, Int> = mapOf(
    "https://guardianproject-wind.s3.amazonaws.com/fdroid/repo" to R.drawable.ic_tabler_apps,
    "https://thecapslock.gitlab.io/fdroid-patched-apps/fdroid/repo" to R.drawable.ic_tabler_apps,
)

/** The bundled drawable icon for a repo [address] (trailing slash ignored), or null when none applies. */
internal fun defaultRepoIconRes(address: String): Int? =
    DEFAULT_REPO_ICON_RES[address.trimEnd('/')]

/**
 * A curated display name for a repo whose own self-declared index name is confusing or unhelpful, so the
 * name we show never regresses once the repo is synced. Patched Apps' index names itself "langis" (the
 * maintainer's personal handle, unrelated to what the repo actually is), which would otherwise silently
 * replace our clearer seeded name "Patched Apps" the moment the repo is enabled and synced. Keyed by repo
 * address, trailing slash ignored.
 */
internal val DEFAULT_REPO_NAMES: Map<String, String> = mapOf(
    "https://thecapslock.gitlab.io/fdroid-patched-apps/fdroid/repo" to "Patched Apps",
)

/** The curated display name for a repo [address] (trailing slash ignored), or null when none applies. */
internal fun defaultRepoName(address: String): String? =
    DEFAULT_REPO_NAMES[address.trimEnd('/')]

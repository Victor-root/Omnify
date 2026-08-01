package com.looker.droidify.data.backup

/**
 * One independently selectable slice of app data a backup can cover. Deliberately separates
 * [FAVOURITES], [HIDDEN_APPS], and the enabled/disabled state carried by [REPOSITORIES] out of
 * [SETTINGS] even though all three are technically stored alongside the rest of
 * [com.looker.droidify.datastore.Settings]: they're user *data*, not app *preferences*, and the user
 * should be able to back up or restore one without the other (e.g. restore favourites on a fresh install
 * without dragging along the old device's proxy config). No android/UI reference here on purpose. It
 * mirrors [com.looker.droidify.datastore.model.Theme] and its siblings, which are plain enums with the
 * string mapping kept in the Compose layer instead.
 */
enum class BackupCategory {
    /** Everything configurable on the Settings screen except favourites, hidden apps, and per-repo
     *  enabled state: theme, installer, sync, proxy, translation, and so on. */
    SETTINGS,

    /** Tracked F-Droid-style repositories: address, credentials, and enabled/disabled state. */
    REPOSITORIES,

    /** Tracked GitHub/GitLab/Codeberg sources: both individually-added apps and whole-account sources. */
    EXTERNAL_SOURCES,

    /** Favourited apps (catalogue or external). */
    FAVOURITES,

    /** Apps (catalogue or external) hidden from every listing. */
    HIDDEN_APPS,

    /** Custom app-detail buttons. */
    CUSTOM_BUTTONS,
}

package com.looker.droidify.datastore.model

/** How the home screen's tabs show which tab is which (see the Personalization settings). */
enum class HomeTabsStyle {
    /** Their names only. */
    TEXT,

    /** Their icons only. */
    ICONS,

    /** Each one's icon above its name. Default. */
    ICONS_AND_TEXT,
}

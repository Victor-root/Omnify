package com.looker.droidify.data.model

import com.looker.droidify.sync.v2.model.DefaultName

/**
 * A browsable catalogue category: its stable [defaultName] (the English key used for filtering and
 * for the icon mapping) and its localized display [name] from the repo index (falling back to
 * English). Distinct from [Category], which models an app's own category tags.
 */
data class CatalogCategory(
    val defaultName: DefaultName,
    val name: String,
    /** True when a repository the user added themselves declares this category, as opposed to one of
     *  the several dozen Omnify ships with. It leads the list then, and is drawn with a badge of its
     *  own, since among a hundred catalogue categories theirs is the one they came for. */
    val ownRepo: Boolean,
)

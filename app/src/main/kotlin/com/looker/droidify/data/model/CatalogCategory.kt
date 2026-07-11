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
)

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

/**
 * One row per category and repository declaring it (a category can come from several at once).
 *
 * The address is the whole point: it is what tells a category declared by a repository the user
 * added themselves from one of the several dozen Omnify ships with, which decides where it sits in
 * the categories list.
 */
data class CategorySource(
    val defaultName: DefaultName,
    val address: String,
)

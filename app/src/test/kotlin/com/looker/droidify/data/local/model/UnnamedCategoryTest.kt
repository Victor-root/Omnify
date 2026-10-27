package com.looker.droidify.data.local.model

import com.looker.droidify.sync.JsonParser
import com.looker.droidify.sync.v2.model.RepoV2
import kotlin.test.Test
import kotlin.test.assertEquals

/** A repository leaving out a category's or an anti-feature's name, as F-Droid's index did for a new
 *  "AI Chat" category: the whole index failed to read, and F-Droid stopped syncing. */
class UnnamedCategoryTest {

    private val repo = JsonParser.decodeFromString(
        RepoV2.serializer(),
        """{
            "address": "https://example.org/repo",
            "timestamp": 1,
            "categories": {
                "AI Chat": {"icon": {}},
                "Games": {"name": {"en-US": "Games", "fr": "Jeux"}}
            },
            "antiFeatures": {"Tracking": {}}
        }""",
    )

    @Test
    fun `an unnamed category is read and named by its key`() {
        val unnamed = repo.categories.getValue("AI Chat").categoryEntity("AI Chat")
        assertEquals(listOf("en-US" to "AI Chat"), unnamed.map { it.locale to it.name })
    }

    @Test
    fun `a named category keeps its own names`() {
        val named = repo.categories.getValue("Games").categoryEntity("Games")
        assertEquals(listOf("en-US" to "Games", "fr" to "Jeux"), named.map { it.locale to it.name })
    }

    @Test
    fun `an unnamed anti-feature is read and named by its tag`() {
        val unnamed = repo.antiFeatures.getValue("Tracking").antiFeatureEntity("Tracking")
        assertEquals(listOf("en-US" to "Tracking"), unnamed.map { it.locale to it.name })
    }
}

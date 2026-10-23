package com.looker.droidify.translation.bergamot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BergamotCatalogTest {

    private fun entry(
        status: String? = "Release",
        model: String = "models/x/model.xy.intgemm.alphas.bin.gz",
        vocab: String? = "models/x/vocab.xy.spm.gz",
    ) = BergamotEntry(
        releaseStatus = status,
        files = BergamotFiles(
            model = BergamotFile(model),
            lexicalShortlist = BergamotFile("models/x/lex.50.50.xy.s2t.bin.gz"),
            vocab = vocab?.let(::BergamotFile),
        ),
    )

    private fun catalog(vararg pairs: Pair<String, List<BergamotEntry>>) =
        BergamotCatalog(baseUrl = "https://example.org", models = mapOf(*pairs))

    @Test
    fun aPairWithItsOwnModelIsTranslatedDirectly() {
        val catalog = catalog("de-fr" to listOf(entry()), "de-en" to listOf(entry()), "en-fr" to listOf(entry()))
        assertEquals(listOf("de" to "fr"), catalog.route("de", "fr"))
    }

    @Test
    fun anyOtherPairGoesThroughEnglish() {
        val catalog = catalog("de-en" to listOf(entry()), "en-fr" to listOf(entry()))
        assertEquals(listOf("de" to "en", "en" to "fr"), catalog.route("de", "fr"))
    }

    @Test
    fun aMissingLegLeavesNoRoute() {
        assertNull(catalog("de-en" to listOf(entry())).route("de", "fr"))
        assertNull(catalog("en-fr" to listOf(entry())).route("fr", "en"))
    }

    @Test
    fun nothingToDoWithinOneLanguage() {
        assertEquals(emptyList(), catalog().route("fr", "fr"))
    }

    @Test
    fun onlyAReleasedModelTheEngineCanRunIsTaken() {
        val nightly = entry(status = "Nightly")
        val desktop = entry(status = "Release Desktop")
        val unknownFormat = entry(model = "models/x/model.xy.bin.gz")
        val noVocab = entry(vocab = null)
        assertNull(catalog("en-fr" to listOf(nightly, desktop, unknownFormat, noVocab)).modelFor("en", "fr"))
    }

    @Test
    fun theAndroidBuildComesFirst() {
        val release = entry(status = "Release")
        val android = entry(status = "Release Android", model = "models/a/model.xy.intgemm.alphas.bin.gz")
        assertEquals(android, catalog("en-fr" to listOf(release, android)).modelFor("en", "fr"))
    }

    @Test
    fun onlyTheDefaultQuantizationRunsAsIs() {
        assertTrue(BergamotFile("a/model.enfr.intgemm.alphas.bin.gz").runsAsIs)
        assertFalse(BergamotFile("a/model.enfr.intgemm8.bin.gz").runsAsIs)
    }

    @Test
    fun separateVocabulariesAreBothKept() {
        val files = BergamotFiles(
            model = BergamotFile("m"),
            lexicalShortlist = BergamotFile("l"),
            srcVocab = BergamotFile("s"),
            trgVocab = BergamotFile("t"),
        )
        assertEquals(listOf("s", "t"), files.vocabs.map { it.path })
    }
}

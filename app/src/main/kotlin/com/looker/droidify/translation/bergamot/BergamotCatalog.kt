package com.looker.droidify.translation.bergamot

import kotlinx.serialization.Serializable

/**
 * Mozilla's list of the translation models it publishes for Firefox's own offline translation: one
 * entry per language pair (`"en-fr"`), each file a path under [baseUrl], gzipped.
 */
@Serializable
internal data class BergamotCatalog(
    val baseUrl: String,
    val models: Map<String, List<BergamotEntry>> = emptyMap(),
)

@Serializable
internal data class BergamotEntry(
    val releaseStatus: String? = null,
    val files: BergamotFiles,
)

@Serializable
internal data class BergamotFiles(
    val model: BergamotFile,
    val lexicalShortlist: BergamotFile,
    val vocab: BergamotFile? = null,
    val srcVocab: BergamotFile? = null,
    val trgVocab: BergamotFile? = null,
) {
    /** One vocabulary shared by both languages, or one per side (the CJK pairs). */
    val vocabs: List<BergamotFile>
        get() = listOfNotNull(vocab).ifEmpty { listOfNotNull(srcVocab, trgVocab) }
}

@Serializable
internal data class BergamotFile(
    val path: String,
    val uncompressedHash: String? = null,
)

/** Whether this model is quantized the way the engine runs by default: every model Mozilla published
 *  was, the day this was written. */
internal val BergamotFile.runsAsIs: Boolean
    get() = path.endsWith(".intgemm.alphas.bin.gz")

/** The released model for [source] to [target] the engine can run, the Android build first. */
internal fun BergamotCatalog.modelFor(source: String, target: String): BergamotEntry? =
    models["$source-$target"].orEmpty()
        .filter { it.releaseStatus in RELEASED }
        .filter { it.files.model.runsAsIs && it.files.vocabs.isNotEmpty() }
        .minByOrNull { RELEASED.indexOf(it.releaseStatus) }

/**
 * The language pairs to go through from [source] to [target]: the pair itself when there is a model for
 * it, through English otherwise (almost every model translates to or from English), null when even that
 * is missing. Empty when there is nothing to translate.
 */
internal fun BergamotCatalog.route(source: String, target: String): List<Pair<String, String>>? = when {
    source == target -> emptyList()
    modelFor(source, target) != null -> listOf(source to target)
    source != ENGLISH && target != ENGLISH &&
        modelFor(source, ENGLISH) != null && modelFor(ENGLISH, target) != null ->
        listOf(source to ENGLISH, ENGLISH to target)
    else -> null
}

internal const val ENGLISH = "en"

/** Released for every platform, or for Android in particular: never a desktop-only or nightly model. */
private val RELEASED = listOf("Release Android", "Release")

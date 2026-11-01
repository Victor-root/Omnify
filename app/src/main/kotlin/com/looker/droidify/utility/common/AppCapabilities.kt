package com.looker.droidify.utility.common

/**
 * Fuzzy "does this app use root" detection, shared by the Discover home's "For rooted devices"
 * carousel ([com.looker.droidify.data.local.dao.AppDao.rootApps]) and the app detail screen's Root
 * badge, so the two agree on what counts. Detecting root is inherently fuzzy: root itself needs no
 * manifest permission (an app just runs `su` at runtime), so the legacy ACCESS_SUPERUSER permission
 * only catches a fraction of root apps — this also matches strong root phrasing (Magisk/KernelSU/
 * "requires root"…) in the app's own text, while excluding negations ("works without root"…) so an
 * app that merely *mentions* root without needing it isn't wrongly flagged.
 */
object RootDetection {

    /** The legacy superuser `<uses-permission>` some root apps still declare. */
    const val PERMISSION = "android.permission.ACCESS_SUPERUSER"

    /** Strong "this app uses root" phrasings, matched case-insensitively, in English and in French
     *  (for the apps whose only text is French). Kept specific enough that a bare "root" (square root,
     *  root directory, root CA…) doesn't leak in. */
    val KEYWORDS = listOf(
        "magisk", "kernelsu", "superuser", "supersu",
        "root access", "root permission", "root privilege", "root required", "requires root",
        "require root", "needs root", "need root", "rooted device", "rooted phone", "root your",
        "nécessite root", "nécessite le root", "accès root", "droits root", "privilèges root",
        "root requis", "root nécessaire", "appareil rooté", "appareils rootés", "téléphone rooté",
    )

    /** Negations that cancel a keyword match, so "works without root" / "no root required" apps
     *  aren't wrongly flagged. */
    val NEGATIONS = listOf(
        "no root", "without root", "non-root", "nonroot", "rootless", "root-free", "root free",
        "not require root", "not need root", "root not required", "no need for root",
        "sans root", "sans accès root", "pas besoin de root", "pas besoin du root", "pas de root",
        "root non requis", "ne nécessite pas root", "ne nécessite pas le root", "non rooté",
    )

    /** True when one of [texts] contains a strong root-usage phrasing not cancelled out by a negation
     *  in that same text. Case-insensitive. Each text is one language's version, judged on its own: an
     *  app's text in the user's language, and its English original, where the translation may have
     *  turned "Requires root" into a phrase not listed here. For an in-memory (not SQL) check: the
     *  detail screen's Root badge, an external app's README/label… */
    fun textIndicatesRoot(vararg texts: String): Boolean = texts.indicate(KEYWORDS, NEGATIONS)
}

/**
 * The same fuzzy detection as [RootDetection], for Shizuku and Sui, which let an app reach system APIs
 * through ADB or root without being root itself. Apps built on them declare the Shizuku API permission
 * (Sui serves the same API), which is the strong signal; their own text is the fallback.
 */
object ShizukuDetection {

    /** Declared by every app using the Shizuku API, whether Shizuku or Sui serves it. */
    const val PERMISSION = "moe.shizuku.manager.permission.API_V23"

    /** "Sui" alone is a word in too many languages to stand for itself, so it is only matched as a
     *  module. */
    val KEYWORDS = listOf("shizuku", "sui module", "module sui")

    val NEGATIONS = listOf(
        "without shizuku", "no shizuku", "shizuku not required", "not require shizuku",
        "not need shizuku", "sans shizuku", "pas besoin de shizuku", "shizuku non requis",
        "ne nécessite pas shizuku",
    )

    /** As [RootDetection.textIndicatesRoot], for Shizuku and Sui. */
    fun textIndicatesShizuku(vararg texts: String): Boolean = texts.indicate(KEYWORDS, NEGATIONS)
}

/** Whether one of these texts holds one of [keywords] and none of [negations], each text judged on its
 *  own and case-insensitively. */
private fun Array<out String>.indicate(keywords: List<String>, negations: List<String>): Boolean = any { text ->
    val lower = text.lowercase()
    keywords.any { lower.contains(it) } && negations.none { lower.contains(it) }
}

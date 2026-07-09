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

    /** Strong "this app uses root" phrasings, matched case-insensitively. Kept specific enough that a
     *  bare "root" (square root, root directory, root CA…) doesn't leak in. */
    val KEYWORDS = listOf(
        "magisk", "kernelsu", "superuser", "supersu",
        "root access", "root permission", "root privilege", "root required", "requires root",
        "require root", "needs root", "need root", "rooted device", "rooted phone", "root your",
    )

    /** Negations that cancel a keyword match, so "works without root" / "no root required" apps
     *  aren't wrongly flagged. */
    val NEGATIONS = listOf(
        "no root", "without root", "non-root", "nonroot", "rootless", "root-free", "root free",
        "not require root", "not need root", "root not required", "no need for root",
    )

    /** True when [text] contains a strong root-usage phrasing not cancelled out by a negation.
     *  Case-insensitive. For an in-memory (not SQL) check — the detail screen's Root badge, an
     *  external app's README/label, … */
    fun textIndicatesRoot(text: String): Boolean {
        val lower = text.lowercase()
        return KEYWORDS.any { lower.contains(it) } && NEGATIONS.none { lower.contains(it) }
    }
}

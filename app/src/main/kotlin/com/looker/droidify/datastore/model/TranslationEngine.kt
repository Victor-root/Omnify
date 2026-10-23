package com.looker.droidify.datastore.model

/**
 * Which backend powers the "Translate" button on an app's description. The user picks one in settings;
 * nothing is pre-downloaded — [MLKIT] fetches its language model on first use, and [LIBRETRANSLATE]
 * needs an instance URL the user provides. [NONE] is the default: translation is off and the Translate
 * button is hidden until the user opts in to an engine.
 */
/** In the order Settings offers them: free software first. Stored by name, so reordering is safe. */
enum class TranslationEngine {
    /** Translation disabled. No engine selected; the Translate button is hidden. Default. */
    NONE,

    /** Mozilla's on-device engine (see BergamotTranslator): offline after a one-time model download
     *  like [MLKIT], but free software with no Google code. Android 9 and 64-bit devices only. */
    BERGAMOT,

    /** Google ML Kit on-device translation. Works offline after a one-time model download. Proprietary. */
    MLKIT,

    /** A user-provided LibreTranslate instance (open source, self-hostable). Online. */
    LIBRETRANSLATE,

    /** Google's free, unofficial translate endpoint. Online, no setup, but not open source. */
    GOOGLE,
}

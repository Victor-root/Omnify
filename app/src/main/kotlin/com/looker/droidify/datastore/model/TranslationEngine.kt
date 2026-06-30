package com.looker.droidify.datastore.model

/**
 * Which backend powers the "Translate" button on an app's description. The user picks one in settings;
 * nothing is pre-downloaded — [MLKIT] fetches its language model on first use, and [LIBRETRANSLATE]
 * needs an instance URL the user provides. [NONE] is the default: translation is off and the Translate
 * button is hidden until the user opts in to an engine.
 */
enum class TranslationEngine {
    /** Translation disabled. No engine selected; the Translate button is hidden. Default. */
    NONE,

    /** Google's free, unofficial translate endpoint. Online, no setup, but not open source. */
    GOOGLE,

    /** A user-provided LibreTranslate instance (open source, self-hostable). Online. */
    LIBRETRANSLATE,

    /** Google ML Kit on-device translation. Works offline after a one-time model download. */
    MLKIT,
}

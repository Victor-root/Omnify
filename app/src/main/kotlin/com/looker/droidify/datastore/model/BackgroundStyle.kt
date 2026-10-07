package com.looker.droidify.datastore.model

/**
 * The abstract backdrop drawn behind the app's screens (see
 * [com.looker.droidify.compose.components.FloatingAppCardsBackground]). Every style is tinted from the
 * user's accent and follows the light, dark and black themes.
 */
enum class BackgroundStyle {
    /** Thin contour lines, like a relief map, over two corner glows. Default. */
    CONTOUR,

    /** Large soft glows in the accent and two neighbouring hues. */
    HALO,

    /** Soft ribbons sweeping across the screen in a wave. */
    SILK,

    /** The original drifting colour wash, on the plain neutral background. */
    AURORA,

    /** Layered hills rising from the bottom of the screen. */
    DUNES,

    /** Scattered out-of-focus discs of light. */
    BOKEH,

    /** Large translucent rounded squares, tilted and overlapping. */
    FACETS,

    /** A fine dot grid that fades away from one corner. */
    DOTS,

    /** Concentric rings spreading slowly from a corner. */
    RIPPLES,

    /** Bundles of parallel wavy lines flowing across the screen. */
    FLOW,
}

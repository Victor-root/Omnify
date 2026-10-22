package com.looker.droidify.data.model

/**
 * Keeps only apps made for TV: one declaring the leanback feature, one its repository gives a TV
 * banner, or one of [launchable], installed here with an entry in the TV launcher.
 *
 * The feature alone missed apps made for phones and TV alike: they declare leanback as not required,
 * and fdroidserver only records required features, so their index entry says nothing of TV. Their
 * banner, or their installed copy, still does.
 */
data class MadeForTv(val launchable: Set<String>)

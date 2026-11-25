package com.looker.droidify.external

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A source as the add dialog takes it in, before it is added: what the dialog starts out filled with.
 * Empty when it is opened by hand, the address alone for a shared link, and the settings too for a link
 * that carries them ([parseObtainiumLink]).
 */
@Parcelize
data class SourceDraft(
    val url: String = "",
    val name: String = "",
    // Null when the link says nothing about pre-releases: the source then takes the default choice.
    val prereleases: PrereleaseMode? = null,
    val apkFilter: String = "",
    val versionIncludeFilter: String = "",
) : Parcelable

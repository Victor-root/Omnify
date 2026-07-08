package com.looker.droidify.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A locally-computed cache of an APK's real UI locales — read once by directly inspecting its
 * compiled resources before the user ever installs it (see
 * [com.looker.droidify.utility.apk.RemoteApkLocaleReader]) — keyed by the APK's own content hash, so
 * it stays valid forever (the hash IS the file's identity) and is never touched by a repo resync,
 * unlike [VersionEntity], which is destructively rebuilt from the index on every sync.
 */
@Entity(tableName = "apk_locale_cache")
data class ApkLocaleCacheEntity(
    @PrimaryKey val apkHash: String,
    val locales: List<String>,
)

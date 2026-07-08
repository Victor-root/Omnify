package com.looker.droidify.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.looker.droidify.data.local.model.ApkLocaleCacheEntity

/** Data Access Object for the cached "real supported locales" of a not-yet-installed APK, keyed by
 *  the APK's own content hash. See [ApkLocaleCacheEntity]. */
@Dao
interface ApkLocaleCacheDao {

    /**
     * The cached locales for the APK with this hash, or null if nothing has been cached for it yet.
     * @param apkHash The APK's content hash (its identity — see [ApkLocaleCacheEntity]).
     */
    @Query("SELECT locales FROM apk_locale_cache WHERE apkHash = :apkHash")
    suspend fun getLocales(apkHash: String): List<String>?

    /**
     * Caches the real supported locales for one APK, keyed by its content hash.
     * @param entry The cache entry to insert or replace.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setLocales(entry: ApkLocaleCacheEntity)
}

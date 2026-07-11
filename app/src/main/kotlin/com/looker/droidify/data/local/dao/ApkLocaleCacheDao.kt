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
    suspend fun getLocales(apkHash: String): List<String>? = getEntry(apkHash)?.locales

    /**
     * Selecting the `locales` column directly (`SELECT locales FROM …`) into a `List<String>` return
     * type is ambiguous for Room: since the column's *own* Kotlin type is already `List<String>` (via
     * a TypeConverter to a JSON-encoded TEXT column), and the DAO method's return type is also
     * `List<String>`, Room reads it as "one un-converted raw String per matching row" rather than
     * "the single row's converted List<String> value" — silently skipping the TypeConverter and
     * returning a one-element list containing the *raw JSON text* itself (e.g. `["fr","de",…]` shown
     * as if it were one locale). Selecting the whole entity sidesteps the ambiguity: a field access
     * always goes through the converter unambiguously.
     */
    @Query("SELECT * FROM apk_locale_cache WHERE apkHash = :apkHash")
    suspend fun getEntry(apkHash: String): ApkLocaleCacheEntity?

    /**
     * Caches the real supported locales for one APK, keyed by its content hash.
     * @param entry The cache entry to insert or replace.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setLocales(entry: ApkLocaleCacheEntity)
}

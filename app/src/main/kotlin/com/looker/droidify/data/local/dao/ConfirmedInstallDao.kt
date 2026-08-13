package com.looker.droidify.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.looker.droidify.data.local.model.ConfirmedInstallEntity

@Dao
interface ConfirmedInstallDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: ConfirmedInstallEntity)

    @Query("SELECT versionCode FROM confirmed_install WHERE packageName = :packageName")
    suspend fun versionCodeOf(packageName: String): Long?

    @Query("DELETE FROM confirmed_install WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

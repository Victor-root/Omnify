package com.looker.droidify.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.looker.droidify.data.local.model.AuthenticationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AuthDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(authentication: AuthenticationEntity)

    @Query("SELECT * FROM authentication WHERE repoId = :repoId")
    suspend fun authFor(repoId: Int): AuthenticationEntity?

    /** Every stored login, re-read whenever one is added, changed or removed. */
    @Query("SELECT * FROM authentication")
    fun stream(): Flow<List<AuthenticationEntity>>
}

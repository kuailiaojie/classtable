package com.kxin.classtable.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SemesterDao {
    @Query("SELECT * FROM semesters ORDER BY startDay DESC LIMIT 1")
    suspend fun latest(): SemesterEntity?

    @Query("SELECT * FROM semesters ORDER BY startDay DESC")
    fun observeAll(): Flow<List<SemesterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(semester: SemesterEntity)
}

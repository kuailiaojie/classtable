package com.kxin.classtable.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface YuketangBindingDao {
    @Query("SELECT * FROM yuketang_bindings")
    fun observeAll(): Flow<List<YuketangBindingEntity>>

    @Query("SELECT * FROM yuketang_bindings")
    suspend fun getAll(): List<YuketangBindingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: YuketangBindingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(bindings: List<YuketangBindingEntity>)

    @Query("DELETE FROM yuketang_bindings WHERE courseId = :courseId")
    suspend fun deleteByCourse(courseId: String)
}

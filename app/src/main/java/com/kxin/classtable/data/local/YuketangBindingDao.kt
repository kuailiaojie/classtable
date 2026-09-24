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

    /** 公告是按班级缓存的,所以给「App 课程 → 班级」的反查留了这两个入口。 */
    @Query("SELECT * FROM yuketang_bindings WHERE courseId = :courseId")
    suspend fun getByCourse(courseId: String): YuketangBindingEntity?

    @Query("SELECT * FROM yuketang_bindings WHERE courseId = :courseId")
    fun observeByCourse(courseId: String): Flow<YuketangBindingEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: YuketangBindingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(bindings: List<YuketangBindingEntity>)

    @Query("DELETE FROM yuketang_bindings WHERE courseId = :courseId")
    suspend fun deleteByCourse(courseId: String)
}

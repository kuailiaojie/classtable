package com.kxin.classtable.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DeletedCourseDao {
    @Query("SELECT * FROM deleted_courses")
    suspend fun getAll(): List<DeletedCourseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(deleted: DeletedCourseEntity)

    @Query("DELETE FROM deleted_courses WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 批量清理到期墓碑(本地回收,见 SyncRepository 的墓碑 GC)。 */
    @Query("DELETE FROM deleted_courses WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

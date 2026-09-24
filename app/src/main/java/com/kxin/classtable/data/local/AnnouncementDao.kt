package com.kxin.classtable.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnouncementDao {
    /** 课程详情页的公告时间轴(最新在前)。 */
    @Query("SELECT * FROM announcements WHERE courseId = :courseId ORDER BY createdAt DESC")
    fun observeByCourse(courseId: String): Flow<List<AnnouncementEntity>>

    /**
     * 课前提醒用:该课程最近的若干条公告。
     *
     * 取一批而不是一条,是因为「雨课堂自己的上课提醒」要在这批里筛掉 —— 词表需要能随时调整,
     * 所以不在 SQL 里写死过滤,由调用方([com.kxin.classtable.data.yuketang.YuketangNoticeFilter])筛。
     */
    @Query("SELECT * FROM announcements WHERE courseId = :courseId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentByCourse(courseId: String, limit: Int): List<AnnouncementEntity>

    @Query("SELECT * FROM announcements WHERE id = :id")
    suspend fun getById(id: String): AnnouncementEntity?

    /** 判断「首次拉取这个班级」用:有缓存才统计新增,免得把历史公告当新公告推。 */
    @Query("SELECT COUNT(*) FROM announcements WHERE classroomId = :classroomId")
    suspend fun countByClassroom(classroomId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(announcements: List<AnnouncementEntity>)

    @Query("DELETE FROM announcements")
    suspend fun clear()
}

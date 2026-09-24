package com.kxin.classtable.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kxin.classtable.data.yuketang.YuketangAnnouncement

/**
 * 已拉取的课程公告(本机缓存)。
 *
 * **按「雨课堂班级」归属,不按 App 课程归属** —— 这是刻意的:
 * 课表里同一门课可能有多条记录(周一一条、周五一条,或者重复添加/导入),它们会各自绑定到
 * 同一个雨课堂班级。若公告挂在 courseId 上,`id` 做不了主键(同一条公告会有 N 个 courseId),
 * 结果就是「只有一条记录能看到公告」。挂在班级上,多条课程记录自然共享同一份。
 *
 * 缓存的意义:课程详情页与**课前提醒**都读本地——提醒由精确闹钟触发,那一刻不能联网。
 */
@Entity(
    tableName = "announcements",
    indices = [Index("classroomId", "createdAt")],
)
data class AnnouncementEntity(
    @PrimaryKey val id: String,
    val classroomId: String,
    val title: String,
    val content: String,
    val publisher: String,
    val createdAt: Long,
    val fetchedAt: Long,
) {
    fun toDomain(): YuketangAnnouncement = YuketangAnnouncement(
        id = id,
        classroomId = classroomId,
        title = title,
        content = content,
        createdAtMillis = createdAt,
        publisher = publisher,
    )

    companion object {
        fun fromDomain(a: YuketangAnnouncement, fetchedAt: Long): AnnouncementEntity =
            AnnouncementEntity(
                id = a.id,
                classroomId = a.classroomId,
                title = a.title,
                content = a.content,
                publisher = a.publisher,
                createdAt = a.createdAtMillis,
                fetchedAt = fetchedAt,
            )
    }
}

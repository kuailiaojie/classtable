package com.kxin.classtable.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kxin.classtable.data.yuketang.YuketangAnnouncement

/**
 * 已拉取的课程公告(本机缓存)。
 *
 * 缓存的意义有二:课程详情页与**课前提醒**都读本地——提醒是精确闹钟触发的,那一刻不能联网,
 * 只读缓存才能保证准时与离线可用。
 */
@Entity(
    tableName = "announcements",
    indices = [Index("courseId"), Index("classroomId")],
)
data class AnnouncementEntity(
    @PrimaryKey val id: String,
    val classroomId: String,
    /** 关联的 App 课程 id(绑定表反查得到)。 */
    val courseId: String,
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
        fun fromDomain(a: YuketangAnnouncement, courseId: String, fetchedAt: Long): AnnouncementEntity =
            AnnouncementEntity(
                id = a.id,
                classroomId = a.classroomId,
                courseId = courseId,
                title = a.title,
                content = a.content,
                publisher = a.publisher,
                createdAt = a.createdAtMillis,
                fetchedAt = fetchedAt,
            )
    }
}

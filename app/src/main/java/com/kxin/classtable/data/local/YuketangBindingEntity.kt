package com.kxin.classtable.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 课程 ↔ 雨课堂班级的绑定。
 *
 * 单独建表而**不给 `courses` 加列**,是因为课程会经 `RemoteCourse` 与 Firestore 双向同步,
 * 本地新增的列会在 pull 覆盖时被清掉;而绑定是「本机 + 本学校」专有数据,只该留在本机。
 */
@Entity(tableName = "yuketang_bindings")
data class YuketangBindingEntity(
    @PrimaryKey val courseId: String,
    val classroomId: String,
    /** 班级名(展示用),便于离线时也能显示绑到了哪个班。 */
    val classroomName: String = "",
    val teacherName: String = "",
    val updatedAt: Long = 0L,
)

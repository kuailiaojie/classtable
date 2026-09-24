package com.kxin.classtable.data.yuketang

/**
 * 雨课堂课程班级(接口 `/v2/api/web/courses/list?identity=2` 的一条)。
 *
 * 一个 App 课程可能对应多个 classroom(同名课不同班),因此绑定必须是「课程 → classroom_id」
 * 的显式映射,不能靠名字隐式推。
 */
data class YuketangCourse(
    val classroomId: String,
    /** 班级名,如「21计算机类地方本科班」。 */
    val name: String,
    /** 课程名,如「计算机网络」。 */
    val courseName: String = "",
    val teacherName: String = "",
) {
    /** 界面上用来区分同名课程的一行说明。 */
    fun label(): String = buildString {
        append(name.ifBlank { courseName.ifBlank { classroomId } })
        if (courseName.isNotBlank() && courseName != name) append(" · ").append(courseName)
        if (teacherName.isNotBlank()) append(" · ").append(teacherName)
    }
}

/** 一条课程公告。 */
data class YuketangAnnouncement(
    val id: String,
    val classroomId: String,
    val title: String,
    val content: String,
    /** 发布时间(epoch millis);解析不出时为 0。 */
    val createdAtMillis: Long,
    val publisher: String = "",
)

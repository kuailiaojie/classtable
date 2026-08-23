package com.kxin.classtable.data

import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.WeekType

/** Firestore 课程文档(users/{uid}/courses/{id})。全默认值构造,便于 toObject 反序列化。 */
data class RemoteCourse(
    val id: String = "",
    val name: String = "",
    val teacher: String = "",
    val location: String = "",
    val weekday: Int = 1,
    val startPeriod: Int = 1,
    val endPeriod: Int = 1,
    val weekType: String = "EVERY_WEEK",
    val weekStart: Int = 1,
    val weekEnd: Int = 16,
    val semesterId: String = "default",
    val updatedAt: Long = 0L,
    val customStartMinute: Int? = null,
    val customEndMinute: Int? = null,
    /** 星期位掩码;0 = 旧数据,按 weekday 推导。 */
    val weekdays: Int = 0,
    val note: String = "",
) {
    fun toDomain(): Course = Course(
        id = id,
        name = name,
        teacher = teacher,
        location = location,
        weekday = weekday,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        weekType = runCatching { WeekType.valueOf(weekType) }.getOrDefault(WeekType.EVERY_WEEK),
        weekStart = weekStart,
        weekEnd = weekEnd,
        semesterId = semesterId,
        updatedAt = updatedAt,
        customStartMinute = customStartMinute,
        customEndMinute = customEndMinute,
        weekdays = if (weekdays > 0) weekdays else 1 shl (weekday - 1),
        note = note,
    )

    companion object {
        fun fromDomain(c: Course): RemoteCourse = RemoteCourse(
            id = c.id,
            name = c.name,
            teacher = c.teacher,
            location = c.location,
            weekday = c.weekday,
            startPeriod = c.startPeriod,
            endPeriod = c.endPeriod,
            weekType = c.weekType.name,
            weekStart = c.weekStart,
            weekEnd = c.weekEnd,
            semesterId = c.semesterId,
            updatedAt = c.updatedAt,
            customStartMinute = c.customStartMinute,
            customEndMinute = c.customEndMinute,
            weekdays = c.weekdays,
            note = c.note,
        )
    }
}

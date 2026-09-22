package com.kxin.classtable.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kxin.classtable.domain.WeekSpec
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.WeekType

@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val teacher: String,
    val location: String,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val weekType: String,
    val weekStart: Int,
    val weekEnd: Int,
    /** 自定义的精确周次,CSV(如 "4,6,8");空 = 沿用 weekStart..weekEnd。 */
    val weeks: String = "",
    val semesterId: String,
    val updatedAt: Long,
    val customStartMinute: Int? = null,
    val customEndMinute: Int? = null,
    /** 星期位掩码;0 = 未迁移/旧数据,按 weekday 推导。 */
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
        weeks = WeekSpec.decode(weeks),
        semesterId = semesterId,
        updatedAt = updatedAt,
        customStartMinute = customStartMinute,
        customEndMinute = customEndMinute,
        weekdays = if (weekdays > 0) weekdays else 1 shl (weekday - 1),
        note = note,
    )

    companion object {
        fun fromDomain(c: Course): CourseEntity = CourseEntity(
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
            weeks = WeekSpec.encode(c.weeks),
            semesterId = c.semesterId,
            updatedAt = c.updatedAt,
            customStartMinute = c.customStartMinute,
            customEndMinute = c.customEndMinute,
            weekdays = c.weekdays,
            note = c.note,
        )
    }
}

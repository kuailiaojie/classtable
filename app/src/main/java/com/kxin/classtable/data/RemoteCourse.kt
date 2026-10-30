package com.kxin.classtable.data

import com.kxin.classtable.domain.WeekSpec
import com.kxin.classtable.domain.enumOrDefault
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.WeekType
import org.json.JSONObject

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
    /** 自定义的精确周次,CSV(如 "4,6,8");空 = 沿用 weekStart..weekEnd。 */
    val weeks: String = "",
    val semesterId: String = "default",
    val updatedAt: Long = 0L,
    val customStartMinute: Int? = null,
    val customEndMinute: Int? = null,
    /** 星期位掩码;0 = 旧数据,按 weekday 推导。 */
    val weekdays: Int = 0,
    val note: String = "",
    val colorHex: String = "",
    /** 自动配色钉在课程上的色相(0..359);null = 老记录,渲染时按课名现算。 */
    val colorHue: Int? = null,
) {
    fun toDomain(): Course = Course(
        id = id,
        name = name,
        teacher = teacher,
        location = location,
        weekday = weekday,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        weekType = enumOrDefault(weekType, WeekType.EVERY_WEEK),
        weekStart = weekStart,
        weekEnd = weekEnd,
        weeks = WeekSpec.decode(weeks),
        semesterId = semesterId,
        updatedAt = updatedAt,
        customStartMinute = customStartMinute,
        customEndMinute = customEndMinute,
        weekdays = if (weekdays > 0) weekdays else 1 shl (weekday - 1),
        note = note,
        colorHex = colorHex,
        colorHue = colorHue,
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
            weeks = WeekSpec.encode(c.weeks),
            semesterId = c.semesterId,
            updatedAt = c.updatedAt,
            customStartMinute = c.customStartMinute,
            customEndMinute = c.customEndMinute,
            weekdays = c.weekdays,
            note = c.note,
            colorHex = c.colorHex,
            colorHue = c.colorHue,
        )

        // ---- Firestore REST 字段编解码(org.json,零依赖) ----

        /** fields → RemoteCourse;缺失字段回退到 data class 默认值(与原 toObject 行为一致)。 */
        fun fromFields(fields: JSONObject): RemoteCourse = RemoteCourse(
            id = FirestoreFields.getString(fields, "id"),
            name = FirestoreFields.getString(fields, "name"),
            teacher = FirestoreFields.getString(fields, "teacher"),
            location = FirestoreFields.getString(fields, "location"),
            weekday = FirestoreFields.getInt(fields, "weekday", 1),
            startPeriod = FirestoreFields.getInt(fields, "startPeriod", 1),
            endPeriod = FirestoreFields.getInt(fields, "endPeriod", 1),
            weekType = FirestoreFields.getString(fields, "weekType", "EVERY_WEEK"),
            weekStart = FirestoreFields.getInt(fields, "weekStart", 1),
            weekEnd = FirestoreFields.getInt(fields, "weekEnd", 16),
            weeks = FirestoreFields.getString(fields, "weeks"),
            semesterId = FirestoreFields.getString(fields, "semesterId", "default"),
            updatedAt = FirestoreFields.getLong(fields, "updatedAt"),
            customStartMinute = FirestoreFields.getIntOrNull(fields, "customStartMinute"),
            customEndMinute = FirestoreFields.getIntOrNull(fields, "customEndMinute"),
            weekdays = FirestoreFields.getInt(fields, "weekdays", 0),
            note = FirestoreFields.getString(fields, "note"),
            colorHex = FirestoreFields.getString(fields, "colorHex"),
            colorHue = FirestoreFields.getIntOrNull(fields, "colorHue"),
        )

        /** RemoteCourse → fields;null 字段省略(等于文档中不存在,与 SDK 写 null 语义兼容)。 */
        fun toFields(c: RemoteCourse): JSONObject = JSONObject().apply {
            FirestoreFields.putString(this, "id", c.id)
            FirestoreFields.putString(this, "name", c.name)
            FirestoreFields.putString(this, "teacher", c.teacher)
            FirestoreFields.putString(this, "location", c.location)
            FirestoreFields.putInt(this, "weekday", c.weekday)
            FirestoreFields.putInt(this, "startPeriod", c.startPeriod)
            FirestoreFields.putInt(this, "endPeriod", c.endPeriod)
            FirestoreFields.putString(this, "weekType", c.weekType)
            FirestoreFields.putInt(this, "weekStart", c.weekStart)
            FirestoreFields.putInt(this, "weekEnd", c.weekEnd)
            FirestoreFields.putString(this, "weeks", c.weeks)
            FirestoreFields.putString(this, "semesterId", c.semesterId)
            FirestoreFields.putLong(this, "updatedAt", c.updatedAt)
            c.customStartMinute?.let { FirestoreFields.putInt(this, "customStartMinute", it) }
            c.customEndMinute?.let { FirestoreFields.putInt(this, "customEndMinute", it) }
            FirestoreFields.putInt(this, "weekdays", c.weekdays)
            FirestoreFields.putString(this, "note", c.note)
            FirestoreFields.putString(this, "colorHex", c.colorHex)
            c.colorHue?.let { FirestoreFields.putInt(this, "colorHue", it) }
        }
    }
}

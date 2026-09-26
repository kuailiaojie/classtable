package com.kxin.classtable.data

import com.kxin.classtable.domain.WeekSpec
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
        fun fromFields(fields: JSONObject): RemoteCourse {
            fun s(n: String) = fields.optJSONObject(n)?.optString("stringValue").orEmpty()
            fun i(n: String, def: Int) = fields.optJSONObject(n)?.optString("integerValue")?.toIntOrNull() ?: def
            fun l(n: String) = fields.optJSONObject(n)?.optString("integerValue")?.toLongOrNull() ?: 0L
            fun iOpt(n: String) = fields.optJSONObject(n)?.optString("integerValue")?.toIntOrNull()
            return RemoteCourse(
                id = s("id"),
                name = s("name"),
                teacher = s("teacher"),
                location = s("location"),
                weekday = i("weekday", 1),
                startPeriod = i("startPeriod", 1),
                endPeriod = i("endPeriod", 1),
                weekType = s("weekType").ifBlank { "EVERY_WEEK" },
                weekStart = i("weekStart", 1),
                weekEnd = i("weekEnd", 16),
                weeks = s("weeks"),
                semesterId = s("semesterId").ifBlank { "default" },
                updatedAt = l("updatedAt"),
                customStartMinute = iOpt("customStartMinute"),
                customEndMinute = iOpt("customEndMinute"),
                weekdays = i("weekdays", 0),
                note = s("note"),
                colorHex = s("colorHex"),
                colorHue = iOpt("colorHue"),
            )
        }

        /** RemoteCourse → fields;null 字段省略(等于文档中不存在,与 SDK 写 null 语义兼容)。 */
        fun toFields(c: RemoteCourse): JSONObject = JSONObject().apply {
            fun str(n: String, v: String) = put(n, JSONObject().put("stringValue", v))
            fun int(n: String, v: Int) = put(n, JSONObject().put("integerValue", v.toString()))
            fun long(n: String, v: Long) = put(n, JSONObject().put("integerValue", v.toString()))
            str("id", c.id)
            str("name", c.name)
            str("teacher", c.teacher)
            str("location", c.location)
            int("weekday", c.weekday)
            int("startPeriod", c.startPeriod)
            int("endPeriod", c.endPeriod)
            str("weekType", c.weekType)
            int("weekStart", c.weekStart)
            int("weekEnd", c.weekEnd)
            str("weeks", c.weeks)
            str("semesterId", c.semesterId)
            long("updatedAt", c.updatedAt)
            c.customStartMinute?.let { int("customStartMinute", it) }
            c.customEndMinute?.let { int("customEndMinute", it) }
            int("weekdays", c.weekdays)
            str("note", c.note)
            str("colorHex", c.colorHex)
            c.colorHue?.let { int("colorHue", it) }
        }
    }
}

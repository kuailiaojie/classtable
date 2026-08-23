package com.kxin.classtable.data.importer

import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.WeekType
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/**
 * 适配脚本产出数据 → 本 App 模型。
 * 课程 JSON 契约(社区统一):
 * { name, teacher, position, day(1-7), startSection, endSection, weeks: number[],
 *   isCustomTime?, customStartTime?, customEndTime? }
 */
object ImportParser {

    fun parseCourses(json: String): List<Course> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val name = o.optString("name").trim()
            val day = o.optInt("day")
            val start = o.optInt("startSection")
            val end = o.optInt("endSection", start).coerceAtLeast(start)
            if (name.isEmpty() || day !in 1..7 || start < 1) return@mapNotNull null
            val weeks = optIntList(o, "weeks").filter { it > 0 }
            Course(
                id = "imp-${UUID.randomUUID()}",
                name = name,
                teacher = o.optString("teacher"),
                location = o.optString("position"),
                weekday = day,
                startPeriod = start,
                endPeriod = end,
                weekType = detectWeekType(weeks),
                weekStart = weeks.minOrNull() ?: 1,
                weekEnd = weeks.maxOrNull() ?: 16,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * 预设节次 → "480-530,610-660,..."(每节起止时间对)。
     * 有 endTime 用 endTime,否则用下一节 start(末节 +50)。
     */
    fun parseTimeSlots(json: String): String? = runCatching {
        val arr = JSONArray(json)
        val slots = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val s = parseTimeMinutes(o.optString("startTime")) ?: return@mapNotNull null
            val e = parseTimeMinutes(o.optString("endTime")) ?: return@mapNotNull null
            if (e > s) s to e else null
        }
        // 无 endTime 的旧脚本:补全为 下一节开始 - 末节 +50
        val startsOnly = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            parseTimeMinutes(o.optString("startTime"))
        }.sorted()
        val pairs = if (slots.isNotEmpty()) {
            slots.distinct().sortedBy { it.first }
        } else {
            startsOnly.mapIndexed { i, s ->
                s to (startsOnly.getOrNull(i + 1) ?: (s + 50))
            }
        }
        if (pairs.isEmpty()) null else pairs.joinToString(",") { "${it.first}-${it.second}" }
    }.getOrNull()

    /** 学期配置 → (周数, 开学日 epochDay);无有效信息返回 null。 */
    fun parseCourseConfig(json: String): Pair<Int, Long>? = runCatching {
        val o = JSONObject(json)
        val weeks = o.optInt("semesterTotalWeeks", 0)
        val dateStr = o.optString("semesterStartDate").ifEmpty { o.optString("startDate") }
        val startDay = runCatching {
            if (dateStr.isBlank()) 0L else LocalDate.parse(dateStr.trim().take(10)).toEpochDay()
        }.getOrDefault(0L)
        if (weeks <= 0 && startDay <= 0L) null else (weeks.coerceAtLeast(1) to startDay)
    }.getOrNull()

    private fun optIntList(o: JSONObject, key: String): List<Int> {
        val arr = o.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.optInt(it) }
    }

    fun detectWeekType(weeks: List<Int>): WeekType = when {
        weeks.isEmpty() -> WeekType.EVERY_WEEK
        weeks.size >= 15 && weeks.size == weeks.max() -> WeekType.EVERY_WEEK
        weeks.all { it % 2 == 1 } -> WeekType.ODD_WEEK
        weeks.all { it % 2 == 0 } -> WeekType.EVEN_WEEK
        // 离散周次:近似为 min..max 的 CUSTOM(README 说明)
        else -> WeekType.CUSTOM
    }

    fun parseTimeMinutes(s: String): Int? {
        val p = s.trim().split(":")
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }
}

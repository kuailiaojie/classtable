package com.kxin.classtable.data.importer

import com.kxin.classtable.domain.Schedule
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

    /** 解析结果:除课程外带上「收到多少条 / 丢了多少条 / 为什么失败」,便于给用户明确反馈。 */
    data class ParseResult(
        val courses: List<Course>,
        val received: Int,
        val dropped: Int,
        val error: String? = null,
    )

    fun parseCourses(json: String): ParseResult {
        val arr = runCatching { JSONArray(json) }.getOrElse { e ->
            return ParseResult(emptyList(), 0, 0, e.message ?: "JSON 格式无法解析")
        }
        val out = ArrayList<Course>(arr.length())
        var dropped = 0
        for (i in 0 until arr.length()) {
            val obj = runCatching { arr.getJSONObject(i) }.getOrNull()
            val course = obj?.let { toCourse(it) }
            if (course == null) dropped++ else out.add(course)
        }
        return ParseResult(out, arr.length(), dropped)
    }

    /** 单条 → 课程;信息不完整返回 null(计入 dropped,不再静默丢弃)。 */
    private fun toCourse(o: JSONObject): Course? {
        val name = o.optString("name").trim()
        val day = o.optInt("day")
        if (name.isEmpty() || day !in 1..7) return null
        val weeks = optIntList(o, "weeks").filter { it > 0 }

        val cs = parseTimeMinutes(o.optString("customStartTime"))
        val ce = parseTimeMinutes(o.optString("customEndTime"))
        // 自定义时间课程(如晚间讲座):没有节次号,之前会被当作非法记录丢掉
        if (o.optBoolean("isCustomTime") || (cs != null && ce != null)) {
            if (cs == null || ce == null || ce <= cs) return null
            return Course(
                id = "imp-${UUID.randomUUID()}",
                name = name,
                teacher = o.optString("teacher"),
                location = o.optString("position"),
                weekday = day,
                startPeriod = 0,
                endPeriod = 0,
                weekType = detectWeekType(weeks),
                weekStart = weeks.minOrNull() ?: 1,
                weekEnd = weeks.maxOrNull() ?: 16,
                customStartMinute = cs,
                customEndMinute = ce,
            )
        }

        val start = o.optInt("startSection")
        val end = o.optInt("endSection", start).coerceAtLeast(start)
        if (start < 1) return null
        return Course(
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

    /**
     * 预设节次 → "480-530,610-660,..."(每节起止时间对)。
     * 有 endTime 用 endTime;缺 endTime 的用下一节开始,末节 +50 分钟。
     */
    fun parseTimeSlots(json: String): String? = runCatching {
        val arr = JSONArray(json)
        val raw = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val s = parseTimeMinutes(o.optString("startTime")) ?: return@mapNotNull null
            s to parseTimeMinutes(o.optString("endTime"))
        }.sortedBy { it.first }
        if (raw.isEmpty()) return@runCatching null
        val pairs = raw.mapIndexed { idx, (s, e) ->
            val end = e ?: raw.getOrNull(idx + 1)?.first ?: (s + Schedule.PERIOD_LENGTH_MIN)
            s to end
        }.filter { it.second > it.first }
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

    /**
     * "HH:MM" → 分钟(自 0:00)。
     *
     * 兼容教务接口常见的 "HH:MM:SS"(秒直接丢弃,如 BBGU 的 `startTime` 原样回传)与
     * "H:MM"(如 "8:00")。此前严格要求恰好两段,带秒的时间一律解析失败 → 整个作息被丢弃,
     * 表现为「课程导入成功但没有作息」。
     */
    fun parseTimeMinutes(s: String): Int? {
        val p = s.trim().split(":")
        if (p.size < 2) return null
        val h = p[0].trim().toIntOrNull() ?: return null
        val m = p[1].trim().toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }
}

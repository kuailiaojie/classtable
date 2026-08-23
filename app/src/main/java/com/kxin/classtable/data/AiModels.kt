package com.kxin.classtable.data

import com.kxin.classtable.data.importer.ImportParser
import com.kxin.classtable.domain.model.Course
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** AI 图片解析结果:课程 + 作息时间(可空)。 */
data class AiScheduleResult(
    val courses: List<Course>,
    val periodTimes: String?,   // "480,530,...",null 表示未识别到作息
)

/** 各供应商共用的课表解析提示词。 */
val AI_SCHEDULE_PROMPT = """
    你是课程表解析助手。识别图片中的课程表(打印或手写均可)。
    严格输出 JSON(不要 markdown 代码块、不要多余文字),格式:
    {"courses":[{"name":"课程名","teacher":"教师","position":"教室","day":1,"startSection":1,"endSection":2,"weeks":[1,2,3]}],
     "timeSlots":[{"number":1,"startTime":"08:00","endTime":"08:50"}]}
    规则:day 1=周一..7=周日;startSection/endSection 为小节序号;weeks 为该课所在周次,无法识别则填 1 到 20;
    timeSlots 为每节课的开始/结束时间(图中有作息时间才填,否则给空数组)。
""".trimIndent()

/** 从模型返回文本中提取 JSON 并解析为课程 + 作息。 */
fun parseAiResult(raw: String): AiScheduleResult {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    val json = JSONObject(raw.substring(start, end + 1))

    val coursesArr = json.optJSONArray("courses") ?: JSONArray()
    val courses = (0 until coursesArr.length()).mapNotNull { i ->
        val o = coursesArr.getJSONObject(i)
        val name = o.optString("name").trim()
        val day = o.optInt("day")
        val startSec = o.optInt("startSection")
        val endSec = o.optInt("endSection", startSec).coerceAtLeast(startSec)
        if (name.isEmpty() || day !in 1..7 || startSec < 1) return@mapNotNull null
        val weeksArr = o.optJSONArray("weeks")
        val weeks = if (weeksArr != null) {
            (0 until weeksArr.length()).map { weeksArr.optInt(it) }.filter { it > 0 }
        } else {
            emptyList()
        }
        Course(
            id = "ai-${UUID.randomUUID()}",
            name = name,
            teacher = o.optString("teacher"),
            location = o.optString("position"),
            weekday = day,
            startPeriod = startSec,
            endPeriod = endSec,
            weekType = ImportParser.detectWeekType(weeks),
            weekStart = weeks.minOrNull() ?: 1,
            weekEnd = weeks.maxOrNull() ?: 16,
        )
    }

    val slotsArr = json.optJSONArray("timeSlots") ?: JSONArray()
    val slots = (0 until slotsArr.length()).mapNotNull { i ->
        val o = slotsArr.getJSONObject(i)
        val s = ImportParser.parseTimeMinutes(o.optString("startTime")) ?: return@mapNotNull null
        val e = ImportParser.parseTimeMinutes(o.optString("endTime")) ?: return@mapNotNull null
        if (e > s) s to e else null
    }.distinct().sortedBy { it.first }

    return AiScheduleResult(
        courses = courses,
        periodTimes = slots.takeIf { it.isNotEmpty() }?.joinToString(",") { "${it.first}-${it.second}" },
    )
}

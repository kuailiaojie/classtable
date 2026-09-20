package com.kxin.classtable.domain

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 一条调休安排:为**某一天**指定当天的上课方式。
 *
 * - [sourceDate] == null:那天**停课**;
 * - [sourceDate] != null:那天**补课**,按 [sourceDate] 那一天(第几周 + 星期几)的课表上课。
 *
 * 注意「原课程日期」指的是教学安排上的那一天,不是自然日:国庆后周六补周四的课,写的就是
 * 周六 → 某个周四。这样调休不需要复制或移动任何课程,课程仍然只属于它的节次与星期。
 */
data class ScheduleAdjustment(
    val date: LocalDate,
    val sourceDate: LocalDate? = null,
    val label: String = "",
) {
    val isRest: Boolean get() = sourceDate == null

    /** 列表里那行副标题:`停课 · 国庆` / `补 9/9 的课`。 */
    fun summary(): String = if (sourceDate == null) {
        "停课" + label.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    } else {
        "补 ${sourceDate.monthValue}/${sourceDate.dayOfMonth} " +
            "(周${WEEKDAY_CHARS[sourceDate.dayOfWeek.value - 1]})的课"
    }

    private companion object {
        const val WEEKDAY_CHARS = "一二三四五六日"
    }
}

/**
 * 某一天实际上课要看哪一天。
 *
 * [effectiveDate] 是**取课依据的日期**:自然日就是它自己,补课日则是原课程日期。
 * [rest] 为真表示当天不上课;[makeup] 为真表示这天是补课日(界面要标出来)。
 */
data class DaySchedule(
    val effectiveDate: LocalDate,
    val rest: Boolean,
    val makeup: Boolean,
)

object Adjustments {

    fun decode(json: String): List<ScheduleAdjustment> = runCatching {
        if (json.isBlank()) return emptyList()
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val date = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull()
                ?: return@mapNotNull null
            val source = o.optString("source").takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ScheduleAdjustment(date, source, o.optString("label"))
        }.distinctBy { it.date }.sortedBy { it.date }
    }.getOrDefault(emptyList())

    fun encode(items: List<ScheduleAdjustment>): String {
        val arr = JSONArray()
        items.sortedBy { it.date }.forEach { a ->
            arr.put(
                JSONObject().apply {
                    put("date", a.date.toString())
                    a.sourceDate?.let { put("source", it.toString()) }
                    if (a.label.isNotBlank()) put("label", a.label)
                },
            )
        }
        return arr.toString()
    }

    /**
     * [date] 这一天怎么过。没有安排就是普通的一天。
     *
     * 原课程日期自身也可能是调休日(补课的课又被调走),所以沿链往上追一层 ——
     * 追之前把当前这条剔除,保证不会绕回自己死循环。
     */
    fun resolve(items: List<ScheduleAdjustment>, date: LocalDate): DaySchedule {
        val entry = items.firstOrNull { it.date == date }
            ?: return DaySchedule(date, rest = false, makeup = false)
        val source = entry.sourceDate ?: return DaySchedule(date, rest = true, makeup = false)
        val chain = resolve(items.filterNot { it.date == date }, source)
        return DaySchedule(chain.effectiveDate, rest = chain.rest, makeup = true)
    }

    /**
     * 某一天实际上课要取「第几周 + 星期几」;停课返回 null。
     * 周视图 / 今日课表 / 小组件都走这一个入口,保证各处一致。
     */
    fun teachingDay(
        items: List<ScheduleAdjustment>,
        date: LocalDate,
        semesterStartDay: Long,
        weekCount: Int,
    ): Pair<Int, Int>? {
        val day = resolve(items, date)
        if (day.rest) return null
        val week = Schedule.weekOf(day.effectiveDate.toEpochDay(), semesterStartDay, weekCount)
        return week to day.effectiveDate.dayOfWeek.value
    }
}

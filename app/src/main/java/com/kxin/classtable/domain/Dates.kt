package com.kxin.classtable.domain

import java.time.LocalDate

/**
 * 中文日期 / 星期格式化,全应用唯一来源。
 *
 * 之前 `"一二三四五六日"[day - 1]` 在 12+ 处各自内联(`Schedule`、`Adjustments`、`AgendaFormat`、
 * `CalendarStrip`、`WeekGrid`、`DayList`、`TimetableScreen`、`AdjustmentsScreen`、`CourseDetailScreen`、
 * `WidgetData`、`YohakuDatePicker` …),改一处文案要改遍全项目。数字一律按 ISO(1 = 周一 … 7 = 周日)。
 */
object Dates {
    /** 星期一~星期日(数字序)。 */
    const val WEEKDAY_CHARS = "一二三四五六日"

    /** 1..7 → "一".."日";越界回落空串。 */
    fun weekdayChar(day: Int): String = WEEKDAY_CHARS.getOrNull(day - 1)?.toString() ?: ""

    /** 1..7 → "周一".."周日"。 */
    fun weekdayLabel(day: Int): String = "周${weekdayChar(day)}"

    /** "9月23日" */
    fun monthDay(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"

    /** "9月23日 周三" */
    fun monthDayWeek(date: LocalDate): String = "${monthDay(date)} ${weekdayLabel(date.dayOfWeek.value)}"

    /** "9/23"(紧凑,省掉星期) */
    fun slash(date: LocalDate): String = "${date.monthValue}/${date.dayOfMonth}"

    /** "9/23 周三" */
    fun slashWeek(date: LocalDate): String = "${slash(date)} ${weekdayLabel(date.dayOfWeek.value)}"

    /** 相对某天的口语标签:「今天 / 明天 / 后天 / 昨天」,其余回落「周X」。 */
    fun relativeDay(date: LocalDate, today: LocalDate): String =
        when (date.toEpochDay() - today.toEpochDay()) {
            0L -> "今天"
            1L -> "明天"
            2L -> "后天"
            -1L -> "昨天"
            else -> weekdayLabel(date.dayOfWeek.value)
        }

    /** 相对某天还有几天(负数 = 已过)。 */
    fun daysUntil(date: LocalDate, today: LocalDate): Long = date.toEpochDay() - today.toEpochDay()
}

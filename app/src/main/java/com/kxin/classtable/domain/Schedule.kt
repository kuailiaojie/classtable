package com.kxin.classtable.domain

import com.kxin.classtable.domain.model.Course
import java.time.LocalDate
import java.time.LocalTime

/**
 * 作息表 = 时间段列表:每节课都有独立的开始与结束时间,可任意增删,不限于 12 节。
 * 序列化格式:"480-530,610-660,..."(分钟自 0:00);兼容旧格式(纯开始时间列表,
 * end 取下一节开始、末节 +50)。
 */
object Schedule {
    /**
     * 默认作息:每节 50 分钟,含课间与午/晚餐间隔。
     * 08:00 / 09:00 / 10:10 / 11:10 · 14:00 / 15:00 / 16:10 / 17:10 · 19:00 / 20:00 / 21:00 / 22:00
     */
    const val DEFAULT_PERIODS =
        "480-530,540-590,610-660,670-720,840-890,900-950,970-1020,1030-1080,1140-1190,1200-1250,1260-1310,1320-1370"

    /**
     * 0.1.16 及更早版本的默认作息(**错的**):它是由「只有开始时间」的旧格式序列化出来的,
     * 偶数节的 end 被写成下一节/下一组的开始时间,于是第2节变 08:50–10:10、**第4节 11:00–14:00
     * (横跨午饭)**、第8节 17:00–19:00,且整条时间线没有任何课间。
     * 命中这个值的存量数据一律视为「没设置过」,交给 [DEFAULT_PERIODS]。
     */
    const val LEGACY_DEFAULT_PERIODS =
        "480-530,530-610,610-660,660-840,840-890,890-970,970-1020,1020-1140,1140-1190,1190-1240,1240-1290,1290-1340"

    const val PERIOD_LENGTH_MIN = 50

    /** 一节作息:开始/结束分钟(自 0:00),时长 = end - start。 */
    data class Period(val start: Int, val end: Int) {
        val duration: Int get() = (end - start).coerceAtLeast(1)
    }

    val defaultPeriods: List<Period> = parsePeriods(DEFAULT_PERIODS)

    /** 解析 "480-530,610-660" 或旧格式 "480,530,610,..."。 */
    fun parsePeriods(spec: String): List<Period> {
        val items = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return defaultPeriods
        return if (items.any { it.contains('-') }) {
            items.mapNotNull { item ->
                val p = item.split('-')
                val s = p.getOrNull(0)?.trim()?.toIntOrNull() ?: return@mapNotNull null
                val e = p.getOrNull(1)?.trim()?.toIntOrNull() ?: (s + PERIOD_LENGTH_MIN)
                if (e > s) Period(s, e) else null
            }
        } else {
            val starts = items.mapNotNull { it.toIntOrNull() }
            starts.mapIndexed { i, s ->
                val e = starts.getOrNull(i + 1) ?: (s + PERIOD_LENGTH_MIN)
                Period(s, e)
            }
        }
    }

    /** 序列化为 "480-530,610-660,..."。 */
    fun serializePeriods(periods: List<Period>): String = periods.joinToString(",") { "${it.start}-${it.end}" }

    /** 大节行:每 2 个小节一行,末行可为单节;节数不限于 12 */
    fun bigPeriods(periodCount: Int): List<Pair<Int, Int>> {
        val count = periodCount.coerceAtLeast(1)
        return (1..count step 2).map { it to minOf(it + 1, count) }
    }

    /** 小节区间 → "08:00–08:50" 等宽时间串 */
    fun periodRange(periods: List<Period> = defaultPeriods, startPeriod: Int, endPeriod: Int): String {
        val s = periods.getOrNull(startPeriod - 1)?.start ?: periods.firstOrNull()?.start ?: 480
        val e = periods.getOrNull(endPeriod - 1)?.end ?: (s + PERIOD_LENGTH_MIN)
        return "${fmt(s)}–${fmt(e)}"
    }

    /** 分钟区间 → "18:30–20:00" */
    fun timeRangeText(startMinute: Int, endMinute: Int): String = "${fmt(startMinute)}–${fmt(endMinute)}"

    /** "HH:MM" → 分钟(自 0:00);无法解析返回 null。 */
    fun parseClock(s: String): Int? {
        val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").find(s) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        return if (h in 0..23 && min in 0..59) h * 60 + min else null
    }

    /** 分钟 → "HH:MM" */
    fun clockText(minute: Int): String = fmt(minute)

    /**
     * 把课程的「节次」按 [periods] 换算成具体时刻并钉住(custom*Minute)。
     *
     * 课程的时刻是**数据**,不是每次显示时现算的派生值:导入/新建时用当时的作息算一次就固定下来,
     * 之后再改作息(或再导入别的学校)都不会把它带走 —— 想改只能编辑这门课本身。
     * 自定时间课程(startPeriod <= 0)与节次不在表内的原样返回。
     */
    fun pinCourseTimes(course: Course, periods: List<Period>): Course {
        if (course.startPeriod <= 0) return course
        val start = periods.getOrNull(course.startPeriod - 1)?.start ?: return course
        val end = periods.getOrNull(course.endPeriod - 1)?.end ?: return course
        return course.copy(customStartMinute = start, customEndMinute = end)
    }

    /** 课程时间文本:自定义时间优先,否则按作息节次。 */
    fun courseTimeText(course: Course, periods: List<Period> = defaultPeriods): String {
        val cs = course.customStartMinute
        val ce = course.customEndMinute
        return if (cs != null && ce != null) {
            timeRangeText(cs, ce)
        } else {
            periodRange(periods, course.startPeriod, course.endPeriod)
        }
    }

    /** 课程开始分钟(自定义时间优先,否则按作息节次);无作息信息返回 null。 */
    fun courseStartMinute(course: Course, periods: List<Period> = defaultPeriods): Int? =
        course.customStartMinute ?: periods.getOrNull(course.startPeriod - 1)?.start

    /** 课程结束分钟(自定义时间优先,否则按作息节次);无作息信息返回 null。 */
    fun courseEndMinute(course: Course, periods: List<Period> = defaultPeriods): Int? =
        course.customEndMinute ?: periods.getOrNull(course.endPeriod - 1)?.end

    /** 课程是否落在某个大节行的时间范围内(自定义时间课程按分钟比对)。 */
    fun courseOverlapsBigPeriod(course: Course, p1: Int, p2: Int, periods: List<Period>): Boolean {
        val cs = course.customStartMinute
        val ce = course.customEndMinute
        if (cs == null || ce == null) return course.overlapsPeriod(p1, p2)
        val rowStart = periods.getOrNull(p1 - 1)?.start ?: 0
        val rowEnd = periods.getOrNull(p2 - 1)?.end ?: (rowStart + PERIOD_LENGTH_MIN)
        return cs < rowEnd && ce > rowStart
    }

    /** 当前时刻所在小节序号(1..节数) */
    fun currentPeriodIndex(periods: List<Period> = defaultPeriods): Int {
        val minutes = LocalTime.now().hour * 60 + LocalTime.now().minute
        var idx = 0
        for ((i, period) in periods.withIndex()) {
            if (minutes >= period.start) idx = i + 1
        }
        return idx
    }

    /** 当前时刻所在大节序号(1..行数) */
    fun currentBigPeriodIndex(periods: List<Period> = defaultPeriods): Int {
        val minutes = LocalTime.now().hour * 60 + LocalTime.now().minute
        var idx = 0
        for ((i, big) in bigPeriods(periods.size).withIndex()) {
            val start = periods.getOrNull(big.first - 1)?.start ?: 0
            if (minutes >= start) idx = i + 1
        }
        return idx
    }

    /** 自定义时间课程重叠的最小节序号;不重叠返回 0。 */
    fun firstOverlapPeriod(course: Course, periods: List<Period>): Int {
        if (!course.hasCustomTime()) return course.startPeriod
        for ((i, _) in periods.withIndex()) {
            val p = i + 1
            if (courseOverlapsBigPeriod(course, p, p, periods)) return p
        }
        return 0
    }

    /** 自定义时间课程重叠的最大节序号;不重叠返回 0。 */
    fun lastOverlapPeriod(course: Course, periods: List<Period>): Int {
        if (!course.hasCustomTime()) return course.endPeriod
        var last = 0
        for ((i, _) in periods.withIndex()) {
            val p = i + 1
            if (courseOverlapsBigPeriod(course, p, p, periods)) last = p
        }
        return last
    }

    /** 当前时刻是否在该课程的时间区间内(自定义课按起止,普通课按节次)。 */
    fun isCourseOngoing(course: Course, periods: List<Period> = defaultPeriods): Boolean {
        val now = LocalTime.now().hour * 60 + LocalTime.now().minute
        val s = course.customStartMinute ?: periods.getOrNull(course.startPeriod - 1)?.start ?: return false
        val e = course.customEndMinute ?: periods.getOrNull(course.endPeriod - 1)?.end ?: return false
        return now >= s && now < e
    }

    /**
     * 自定义时间课程在网格中的布局,返回 (top, height),单位 = 一节行高的倍数。
     * 起点/终点未对齐节次节点时按分钟比例精确定位(如 8:30 落在第 1 节行内的 30/50 处)。
     * top 相对第一节行顶部;不重叠或参数非法返回 (0f, 0f)。
     */
    fun customCourseLayout(course: Course, periods: List<Period>): Pair<Float, Float> {
        val cs = course.customStartMinute ?: return 0f to 0f
        val ce = course.customEndMinute ?: return 0f to 0f
        if (periods.isEmpty()) return 0f to 0f
        val first = firstOverlapPeriod(course, periods).coerceIn(1, periods.size)
        val last = lastOverlapPeriod(course, periods).coerceIn(first, periods.size)
        val dFirst = periods[first - 1].duration
        val dLast = periods[last - 1].duration
        val fracStart = ((cs - periods[first - 1].start).toFloat() / dFirst).coerceIn(0f, 1f)
        val fracEnd = ((ce - periods[last - 1].start).toFloat() / dLast).coerceIn(0f, 1f)
        val top = (first - 1) + fracStart
        val height = (last - first + 1) - fracStart - (1f - fracEnd)
        return top to height.coerceAtLeast(0.5f)
    }

    /** 某 epochDay 所在周的周一(ISO 周一对齐)。周次/周范围统一以它为锚。 */
    fun mondayEpochDay(epochDay: Long): Long =
        epochDay - (LocalDate.ofEpochDay(epochDay).dayOfWeek.value - 1)

    /** 指定日期(epochDay)是学期第几周:以「开学日所在周的周一」为第 1 周起点。 */
    fun weekOf(epochDay: Long, startDay: Long, weekCount: Int): Int {
        if (startDay <= 0L) return 1
        val weeks = ((mondayEpochDay(epochDay) - mondayEpochDay(startDay)) / 7 + 1).toInt()
        return weeks.coerceIn(1, weekCount.coerceAtLeast(1))
    }

    /** 由学期起始日(epochDay)推导当前周(周一对齐);未设置返回 1 */
    fun currentWeek(startDay: Long, weekCount: Int): Int =
        weekOf(LocalDate.now().toEpochDay(), startDay, weekCount)

    /** 第 N 周的日期范围文本(周一~周日),如 "9/14–9/20";未设置学期返回空串 */
    fun weekRangeText(startDay: Long, week: Int): String {
        if (startDay <= 0L) return ""
        val start = LocalDate.ofEpochDay(mondayEpochDay(startDay) + (week - 1) * 7)
        val end = start.plusDays(6)
        return "${start.monthValue}/${start.dayOfMonth}–${end.monthValue}/${end.dayOfMonth}"
    }

    fun todayWeekday(): Int = LocalDate.now().dayOfWeek.value

    /**
     * 绝对分钟 → 周视图网格的行坐标(行高倍数),用于「当前时间线」。
     * 落在节内按分钟比例;落在课间取下一行顶部;在第一节之前或末节之后返回 null(不画线)。
     */
    fun fractionalRow(minute: Int, periods: List<Period>): Float? {
        if (periods.isEmpty()) return null
        if (minute < periods.first().start || minute >= periods.last().end) return null
        periods.forEachIndexed { i, p ->
            if (minute in p.start until p.end) return i + (minute - p.start).toFloat() / p.duration
            if (minute < p.start) return i.toFloat()
        }
        return null
    }

    /** 第 N 周七天的日号(周视图表头用);未设置开学日返回空表。 */
    fun weekDayNumbers(startDay: Long, week: Int): List<Int> {
        if (startDay <= 0L) return emptyList()
        val monday = LocalDate.ofEpochDay(mondayEpochDay(startDay) + (week - 1) * 7L)
        return (0..6).map { monday.plusDays(it.toLong()).dayOfMonth }
    }

    /** "9月23日 周三" */
    fun todayDateText(): String {
        val d = LocalDate.now()
        return "${d.monthValue}月${d.dayOfMonth}日 周${"一二三四五六日"[d.dayOfWeek.value - 1]}"
    }

    private fun fmt(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
}

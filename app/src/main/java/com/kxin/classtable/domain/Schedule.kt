package com.kxin.classtable.domain

import com.kxin.classtable.domain.model.Course
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

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

    /**
     * 一节作息:[start]/[end] 是开始与结束分钟(自 0:00),[number] 是**节次号**。
     *
     * 节次号与行顺序是**两件事**:列表按时间排序(课表从上到下就是一天的时间顺序),
     * 而节次号来自教务本身(第几节就是第几节)。解耦之后,教务把 17:45 那行排在第 8 行,
     * App 里它就仍然叫第 8 节 —— 不会因为按时间排到了第 5 位就被改叫第 5 节。
     * [number] = 0 表示"没写节次号",解析时按时间顺序自动补 1、2、3…
     */
    data class Period(val start: Int, val end: Int, val number: Int = 0) {
        val duration: Int get() = (end - start).coerceAtLeast(1)
    }

    val defaultPeriods: List<Period> = parsePeriods(DEFAULT_PERIODS)

    /** 解析 "480-530:1,610-660:2"(节次号可省略)或旧格式 "480,530,610,…"。 */
    fun parsePeriods(spec: String): List<Period> {
        val items = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return defaultPeriods
        val parsed = if (items.any { it.contains('-') }) {
            items.mapNotNull { item ->
                // 行内可带节次号:"480-530:1" —— 冒号后面那个数才是节次号
                val colon = item.lastIndexOf(':')
                val range = if (colon >= 0) item.substring(0, colon) else item
                val number = if (colon >= 0) item.substring(colon + 1).trim().toIntOrNull() ?: 0 else 0
                val p = range.split('-')
                val s = p.getOrNull(0)?.trim()?.toIntOrNull() ?: return@mapNotNull null
                val e = p.getOrNull(1)?.trim()?.toIntOrNull() ?: (s + PERIOD_LENGTH_MIN)
                if (e > s) Period(s, e, number) else null
            }
        } else {
            val starts = items.mapNotNull { it.toIntOrNull() }
            starts.mapIndexed { i, s ->
                val e = starts.getOrNull(i + 1) ?: (s + PERIOD_LENGTH_MIN)
                Period(s, e)
            }
        }
        return normalizePeriods(parsed)
    }

    /** 序列化为 "480-530:1,610-660:2,…"(节次号写在每一行里)。 */
    fun serializePeriods(periods: List<Period>): String =
        normalizePeriods(periods).joinToString(",") { "${it.start}-${it.end}:${it.number}" }

    /**
     * 规整作息表:按开始时间排序,并给**没写节次号**的行补号(1、2、3…,跳过已占用的号)。
     *
     * 旧数据(没有节次号)走这里补号,结果与"第几行就是第几节"完全一致 —— 升级无迁移成本。
     */
    fun normalizePeriods(periods: List<Period>): List<Period> {
        val sorted = periods.sortedBy { it.start }
        val used = sorted.filter { it.number >= 1 }.map { it.number }.toMutableSet()
        var next = 1
        return sorted.map { p ->
            if (p.number >= 1) {
                p
            } else {
                while (next in used) next++
                used += next
                p.copy(number = next)
            }
        }
    }

    /** 第 [number] 节所在的行下标(0-based);表里没有这一节返回 -1。 */
    fun rowOf(periods: List<Period>, number: Int): Int = periods.indexOfFirst { it.number == number }

    /** 第 [number] 节的时段;表里没有这一节返回 null。 */
    fun periodOf(periods: List<Period>, number: Int): Period? = periods.getOrNull(rowOf(periods, number))

    /** 小节区间 → "08:00–08:50" 等宽时间串 */
    fun periodRange(periods: List<Period> = defaultPeriods, startPeriod: Int, endPeriod: Int): String {
        val s = periodOf(periods, startPeriod)?.start ?: periods.firstOrNull()?.start ?: 480
        val e = periodOf(periods, endPeriod)?.end ?: (s + PERIOD_LENGTH_MIN)
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
        val start = periodOf(periods, course.startPeriod)?.start ?: return course
        val end = periodOf(periods, course.endPeriod)?.end ?: return course
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
        course.customStartMinute ?: periodOf(periods, course.startPeriod)?.start

    /** 课程结束分钟(自定义时间优先,否则按作息节次);无作息信息返回 null。 */
    fun courseEndMinute(course: Course, periods: List<Period> = defaultPeriods): Int? =
        course.customEndMinute ?: periodOf(periods, course.endPeriod)?.end

    /**
     * 自定义时间课程覆盖到的第一**行**(1-based 行号);不重叠返回 0。
     *
     * 返回行号而不是节次号:网格是按行排版的,节次号只用于「课程属于第几节」这件事。
     */
    fun firstOverlapPeriod(course: Course, periods: List<Period>): Int {
        if (!course.hasCustomTime()) return rowOf(periods, course.startPeriod) + 1
        val cs = course.customStartMinute ?: return 0
        val ce = course.customEndMinute ?: return 0
        for ((i, p) in periods.withIndex()) {
            if (p.start < ce && p.end > cs) return i + 1
        }
        return 0
    }

    /** 自定义时间课程覆盖到的最后**行**(1-based 行号);不重叠返回 0。 */
    fun lastOverlapPeriod(course: Course, periods: List<Period>): Int {
        if (!course.hasCustomTime()) return rowOf(periods, course.endPeriod) + 1
        val cs = course.customStartMinute ?: return 0
        val ce = course.customEndMinute ?: return 0
        var last = 0
        for ((i, p) in periods.withIndex()) {
            if (p.start < ce && p.end > cs) last = i + 1
        }
        return last
    }

    /** 当前时刻是否在该课程的时间区间内(自定义课按起止,普通课按节次)。 */
    fun isCourseOngoing(course: Course, periods: List<Period> = defaultPeriods): Boolean {
        val now = LocalTime.now().hour * 60 + LocalTime.now().minute
        val s = course.customStartMinute ?: periodOf(periods, course.startPeriod)?.start ?: return false
        val e = course.customEndMinute ?: periodOf(periods, course.endPeriod)?.end ?: return false
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
     * 时间戳 → **本地**「自 0:00 起的分钟数」。
     *
     * 别写成 `millis / 60_000 % 1440` —— 那是 UTC 的分钟数,在东八区整整差 8 小时:
     * 周视图的「当前时间线」会画到别的行上(甚至落不到任何一节课里、干脆不画),
     * 日视图的「正在上课 / 距下一节还有多久」也会跟着错。
     */
    fun minuteOfDay(millis: Long): Int =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).let { it.hour * 60 + it.minute }

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

    /** 第 N 周七天对应的日期(调休要按具体某天查);未设置开学日返回空表。 */
    fun weekDates(startDay: Long, week: Int): List<LocalDate> {
        if (startDay <= 0L) return emptyList()
        val monday = LocalDate.ofEpochDay(mondayEpochDay(startDay) + (week - 1) * 7L)
        return (0..6).map { monday.plusDays(it.toLong()) }
    }

    /**
     * [epochDay] 是否落在学期区间内(第 1 周的周一起,共 [weekCount] 周)。
     * 未设置开学日时无从判断,一律返回 true。
     */
    fun inTerm(epochDay: Long, startDay: Long, weekCount: Int): Boolean {
        if (startDay <= 0L) return true
        val firstMonday = mondayEpochDay(startDay)
        val lastSunday = firstMonday + weekCount.coerceAtLeast(1) * 7L - 1
        return epochDay in firstMonday..lastSunday
    }

    /** "9月23日 周三" */
    fun todayDateText(): String {
        val d = LocalDate.now()
        return "${d.monthValue}月${d.dayOfMonth}日 周${"一二三四五六日"[d.dayOfWeek.value - 1]}"
    }

    private fun fmt(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
}

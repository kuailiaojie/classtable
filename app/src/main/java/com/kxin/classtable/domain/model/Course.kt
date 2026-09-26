package com.kxin.classtable.domain.model

enum class WeekType { EVERY_WEEK, ODD_WEEK, EVEN_WEEK, CUSTOM }

/**
 * 课程。颜色是可选的:空值继续使用按课程名派生的淡彩。
 */
data class Course(
    val id: String,
    val name: String,
    val teacher: String = "",
    val location: String = "",
    val weekday: Int = 1,          // 主星期(多选时的第一个),1=周一 .. 7=周日
    val startPeriod: Int = 1,      // 1-based 小节号(自定义时间课程为 0,按时间排)
    val endPeriod: Int = 1,
    val weekType: WeekType = WeekType.EVERY_WEEK,
    val weekStart: Int = 1,
    val weekEnd: Int = 16,
    /**
     * 精确周次(1-based、升序去重)。只有 [WeekType.CUSTOM] 用它;空表示沿用 [weekStart]..[weekEnd]
     * 这段连续范围(旧数据的形态)。单/双周请用 [WeekType.ODD_WEEK] / [WeekType.EVEN_WEEK] +
     * [weekStart]..[weekEnd] 表达,例如「第3-19周的双周」。
     */
    val weeks: List<Int> = emptyList(),
    val semesterId: String = "default",
    val updatedAt: Long = 0L,
    /** 非作息时间课程:自定义开始/结束分钟(自 0:00 起),null = 按作息节次。 */
    val customStartMinute: Int? = null,
    val customEndMinute: Int? = null,
    /** 星期位掩码:bit(day-1)=1 表示该星期有课;0 或非法时按 weekday 推导。 */
    val weekdays: Int = 1 shl (weekday - 1),
    /** 备注(选填)。 */
    val note: String = "",
    /** 用户指定的课程颜色(ARGB hex);空值表示使用预设派生色。 */
    val colorHex: String = "",
    /**
     * 自动配色钉在课程上的色相(0..359,OKLCh 色相角)。
     *
     * 分配时保证不与已有课程重复,之后增删课程都不会改变它 —— 与「课程时刻一旦导入就钉死」
     * 是同一个约定。null = 还没钉过(老记录),渲染时按课名现算;用户在设置里点一次
     * 「重新配色」就会给所有课程补上互不重复的色相。
     */
    val colorHue: Int? = null,
) {
    /** 是否带具体时刻。导入/保存时会把时刻钉在课程上,所以「第几节」的课也会是 true。 */
    fun hasCustomTime(): Boolean = customStartMinute != null && customEndMinute != null

    /**
     * 是否为「自定时间」课程:没有节次号,时刻完全由自己给定(如晚间讲座)。
     * 与 [hasCustomTime] 的区别:钉住时刻的普通课程仍属于某个节次,界面上要写「第 N 节」,
     * 只有 startPeriod <= 0 的课才该标成「自定义」。
     */
    fun isCustomScheduled(): Boolean = startPeriod <= 0

    /** 该课程是否在星期 day(1=周一)上课。 */
    fun isOnWeekday(day: Int): Boolean = (weekdays and (1 shl (day - 1))) != 0

    /** 星期集合(1..7,升序)。 */
    fun weekdaysList(): List<Int> = (1..7).filter { isOnWeekday(it) }

    /**
     * 第 [week] 周是否上这门课。
     *
     * 单/双周按**学期绝对周次**算奇偶,并在 [weekStart]..[weekEnd] 内生效 ——
     * 以前范围被忽略,「第3-19周的双周」会在第 2、20 周也显示。
     */
    fun isActiveOnWeek(week: Int): Boolean = when (weekType) {
        WeekType.EVERY_WEEK -> true
        WeekType.ODD_WEEK -> week in weekStart..weekEnd && week % 2 == 1
        WeekType.EVEN_WEEK -> week in weekStart..weekEnd && week % 2 == 0
        WeekType.CUSTOM -> if (weeks.isNotEmpty()) week in weeks else week in weekStart..weekEnd
    }

    /** 是否与 [p1, p2] 小节区间相交(仅按节次课程) */
    fun overlapsPeriod(p1: Int, p2: Int): Boolean = startPeriod <= p2 && endPeriod >= p1

    companion object {
        val WEEKDAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        const val WEEKDAY_CHARS = "一二三四五六日"

        fun weekdayName(day: Int): String = WEEKDAY_NAMES.getOrElse(day - 1) { "" }

        /** "周一、周三、周五" */
        fun weekdaysText(course: Course): String =
            course.weekdaysList().joinToString("、") { weekdayName(it) }

        /** "一三五" 紧凑形式 */
        fun weekdaysShort(course: Course): String =
            course.weekdaysList().joinToString("") { WEEKDAY_CHARS[it - 1].toString() }
    }
}

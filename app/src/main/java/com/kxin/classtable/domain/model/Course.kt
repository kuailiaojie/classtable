package com.kxin.classtable.domain.model

enum class WeekType { EVERY_WEEK, ODD_WEEK, EVEN_WEEK, CUSTOM }

/**
 * 课程。注意:没有 color 字段——Yohaku 路线不给课程分类上色,
 * 强调只属于「当前这一刻」。
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
    val semesterId: String = "default",
    val updatedAt: Long = 0L,
    /** 非作息时间课程:自定义开始/结束分钟(自 0:00 起),null = 按作息节次。 */
    val customStartMinute: Int? = null,
    val customEndMinute: Int? = null,
    /** 星期位掩码:bit(day-1)=1 表示该星期有课;0 或非法时按 weekday 推导。 */
    val weekdays: Int = 1 shl (weekday - 1),
    /** 备注(选填)。 */
    val note: String = "",
) {
    /** 是否自定义时间课程(不随作息表,如临时讲座/晚间加课)。 */
    fun hasCustomTime(): Boolean = customStartMinute != null && customEndMinute != null

    /** 该课程是否在星期 day(1=周一)上课。 */
    fun isOnWeekday(day: Int): Boolean = (weekdays and (1 shl (day - 1))) != 0

    /** 星期集合(1..7,升序)。 */
    fun weekdaysList(): List<Int> = (1..7).filter { isOnWeekday(it) }

    fun isActiveOnWeek(week: Int): Boolean = when (weekType) {
        WeekType.EVERY_WEEK -> true
        WeekType.ODD_WEEK -> week % 2 == 1
        WeekType.EVEN_WEEK -> week % 2 == 0
        WeekType.CUSTOM -> week in weekStart..weekEnd
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

package com.kxin.classtable.ui.agenda

import com.kxin.classtable.domain.model.AgendaEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 日程的开始时刻(系统时区)。 */
internal fun AgendaEvent.startLocal(): LocalDateTime =
    Instant.ofEpochMilli(startAt).atZone(ZoneId.systemDefault()).toLocalDateTime()

/** 日程的结束时刻(系统时区)。 */
internal fun AgendaEvent.endLocal(): LocalDateTime =
    Instant.ofEpochMilli(endAt).atZone(ZoneId.systemDefault()).toLocalDateTime()

/** 该日程是否覆盖某一天(跨天日程落在区间内也算)。 */
internal fun AgendaEvent.spans(date: LocalDate): Boolean {
    val startDay = startLocal().toLocalDate()
    val endDay = endLocal().toLocalDate()
    return date >= startDay && date <= endDay
}

/** 「9月27日 周日」 */
internal fun dateText(date: LocalDate): String =
    "${date.monthValue}月${date.dayOfMonth}日 周${"一二三四五六日"[date.dayOfWeek.value - 1]}"

/** 「今天 / 明天 / 后天 / 昨天 / 周X」 */
internal fun relativeDayLabel(date: LocalDate, today: LocalDate): String = when (daysUntil(date, today)) {
    0L -> "今天"
    1L -> "明天"
    2L -> "后天"
    -1L -> "昨天"
    else -> "周${"一二三四五六日"[date.dayOfWeek.value - 1]}"
}

/** 距离某天还有几天(负数 = 已过)。 */
internal fun daysUntil(date: LocalDate, today: LocalDate): Long = date.toEpochDay() - today.toEpochDay()

/** 「1 小时」「30 分钟」「2 小时 30 分钟」 */
internal fun formatDurationText(minutes: Long): String {
    val m = minutes.coerceAtLeast(0)
    val h = m / 60
    val min = m % 60
    return when {
        h > 0 && min > 0 -> "$h 小时 $min 分钟"
        h > 0 -> "$h 小时"
        else -> "$min 分钟"
    }
}

/** 「10:00–11:40」 */
internal fun timeRangeText(start: LocalDateTime, end: LocalDateTime): String =
    "%02d:%02d–%02d:%02d".format(start.hour, start.minute, end.hour, end.minute)

/** 上午 / 下午 / 晚上 */
internal fun periodLabel(hour: Int): String = when {
    hour < 12 -> "上午"
    hour < 18 -> "下午"
    else -> "晚上"
}

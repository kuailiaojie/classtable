package com.kxin.classtable.data

import com.kxin.classtable.domain.Dates
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.WeekSpec
import com.kxin.classtable.domain.enumOrDefault
import com.kxin.classtable.domain.model.AgendaCategory
import com.kxin.classtable.domain.model.AgendaEvent
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.weeksText
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * AI 助手的「上下文拼装」、「动作 → 领域模型」转换,以及改 / 删课的周次拆分。
 *
 * 纯函数,便于推理:上下文给模型看当前时间与记忆(课程事实改由只读工具按需查,见 [AgentTools]);
 * 动作在用户确认后由这里翻译成 [Course] / [AgendaEvent],再交给仓库落库(与表单保存走同一套约定:
 * 钉住时刻、周次归一)。
 */
object AiPlanner {

    /**
     * 系统提示词里的「本地上下文」:[现在] 与 [助手记忆]。
     *
     * 刻意**不预载课表** —— 那是只读工具的事:模型按需查,省下每轮都塞一遍全学期课程的 token。
     */
    fun buildContext(
        settings: AppSettings,
        memory: List<String>,
        today: LocalDate = LocalDate.now(),
        now: LocalTime = LocalTime.now(),
    ): String {
        val week = Schedule.weekOf(today.toEpochDay(), settings.semesterStartDay, settings.semesterWeekCount)
        val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())

        return buildString {
            append("[现在] ")
                .append(today).append(' ').append(Dates.weekdayLabel(today.dayOfWeek.value))
                .append(" ").append("%02d:%02d".format(now.hour, now.minute)).append("(设备本地时间)")
                .append("\n今天=").append(today)
                .append(",明天=").append(today.plusDays(1))
                .append(",昨天=").append(today.minusDays(1))
                .append("\n本周=").append(weekStart).append(" 至 ").append(weekStart.plusDays(6)).append("(周一至周日)")
                .append("\n当前第 ").append(week).append(" 教学周(共 ").append(settings.semesterWeekCount).append(" 周")
            if (settings.semesterStartDay > 0L) {
                append(",开学日 ").append(LocalDate.ofEpochDay(settings.semesterStartDay))
            }
            append(")。日期与「今天 / 明天 / 本周」一律以此为准。")

            if (memory.isNotEmpty()) {
                append("\n\n[助手记忆] 以下是用户此前让你记住的偏好,可参考但不要当成硬性规则:")
                memory.forEach { append("\n- ").append(it) }
            }
        }
    }

    /** 把建课动作翻成 [Course](已按当前作息钉住时刻、周次归一;多天落成星期掩码)。 */
    fun courseDraft(action: AiAction.CreateCourse, settings: AppSettings): Course {
        val periods = Schedule.parsePeriods(settings.periodTimes)
        val spec = WeekSpec.fromWeeks(action.weeks, settings.semesterWeekCount)
        val days = action.days.ifEmpty { listOf(action.day) }.filter { it in 1..7 }.distinct().sorted()
        val primary = days.firstOrNull() ?: action.day
        val mask = days.fold(0) { acc, d -> acc or (1 shl (d - 1)) }.takeIf { it != 0 } ?: (1 shl (primary - 1))
        val base = Course(
            id = newCourseId(),
            name = action.name,
            teacher = action.teacher,
            location = action.location,
            weekday = primary,
            weekdays = mask,
            startPeriod = action.startSection,
            endPeriod = action.endSection,
            weekType = spec.type,
            weekStart = spec.start,
            weekEnd = spec.end,
            weeks = spec.weeks,
            note = action.note,
        )
        return Schedule.pinCourseTimes(base, periods)
    }

    /** 把建日程动作翻成 [AgendaEvent];日期/时间非法返回 null。 */
    fun agendaDraft(action: AiAction.CreateAgenda, zone: ZoneId = ZoneId.systemDefault()): AgendaEvent? {
        val date = runCatching { LocalDate.parse(action.date) }.getOrNull() ?: return null
        val startMin = action.startTime?.let(Schedule::parseClock)
        val endMin = action.endTime?.let(Schedule::parseClock)
        val allDay = action.startTime == null || action.endTime == null || startMin == null || endMin == null

        val startAt: Long
        val endAt: Long
        if (allDay) {
            startAt = date.atStartOfDay(zone).toInstant().toEpochMilli()
            endAt = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
        } else {
            if (endMin!! <= startMin!!) return null
            startAt = date.atStartOfDay(zone).toInstant().toEpochMilli() + startMin * 60_000L
            endAt = date.atStartOfDay(zone).toInstant().toEpochMilli() + endMin * 60_000L
        }
        return AgendaEvent(
            id = "ai-${UUID.randomUUID()}",
            title = action.title,
            category = enumOrDefault(action.category, AgendaCategory.TODO),
            startAt = startAt,
            endAt = endAt,
            allDay = allDay,
            location = action.location,
            note = action.note,
        )
    }

    /**
     * 把「改日程」动作落成新的 [AgendaEvent](局部补丁:未给的字段保留)。
     *
     * [AiAction.UpdateAgenda.date] 给了就把条目移到这一天(时刻保留);[AiAction.UpdateAgenda.startTime]
     * / [AiAction.UpdateAgenda.endTime] 成对给才改时刻(只给一边视为沿用,非法时间返回 null);
     * [AiAction.UpdateAgenda.allDay] 给了才切换全天 —— 若给出具体时间则默认切回非全天。
     */
    fun applyAgendaPatch(
        existing: AgendaEvent,
        action: AiAction.UpdateAgenda,
        zone: ZoneId = ZoneId.systemDefault(),
    ): AgendaEvent? {
        val currentStart = Instant.ofEpochMilli(existing.startAt).atZone(zone).toLocalDateTime()
        val currentEnd = Instant.ofEpochMilli(existing.endAt).atZone(zone).toLocalDateTime()
        val date = action.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: currentStart.toLocalDate()
        val gaveTime = action.startTime != null || action.endTime != null
        val allDay = action.allDay ?: if (gaveTime) false else existing.allDay

        val startAt: Long
        val endAt: Long
        if (allDay) {
            startAt = date.atStartOfDay(zone).toInstant().toEpochMilli()
            endAt = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
        } else {
            val startMin = action.startTime?.let(Schedule::parseClock) ?: (currentStart.hour * 60 + currentStart.minute)
            val endMin = action.endTime?.let(Schedule::parseClock) ?: (currentEnd.hour * 60 + currentEnd.minute)
            if (endMin <= startMin) return null
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            startAt = dayStart + startMin * 60_000L
            endAt = dayStart + endMin * 60_000L
        }

        return existing.copy(
            title = action.title?.takeIf { it.isNotBlank() } ?: existing.title,
            category = action.category?.let { enumOrDefault(it, existing.category) } ?: existing.category,
            startAt = startAt,
            endAt = endAt,
            allDay = allDay,
            location = action.location ?: existing.location,
            note = action.note ?: existing.note,
        )
    }

    fun newCourseId(): String = "ai-${UUID.randomUUID()}"

    /**
     * 把「改 / 删课」动作落到课程记录上,返回**这门课最终应有的全部记录**。
     *
     * - 整条改 / 删([AiAction.SCOPE_ALL]):原地改;删除返回空表。
     * - 只改部分周([AiAction.SCOPE_SELECTED]):把记录拆成「保留的周」(原 ID)+「改动后的周」
     *   (新 ID);原记录若被拆空就不再出现 —— 调用方据此判断要不要删原记录。
     *
     * 拆分一律写成精确周次([WeekSpec.fromWeeks]),不猜单双周;未改动的记录保持原表示不变。
     */
    fun applyCourseAction(course: Course, action: AiAction, settings: AppSettings): List<Course> {
        val weekCount = settings.semesterWeekCount.coerceAtLeast(1)
        val active = (1..weekCount).filter { course.isActiveOnWeek(it) }
        val periods = Schedule.parsePeriods(settings.periodTimes)

        return when (action) {
            is AiAction.DeleteCourse -> {
                if (action.scope != AiAction.SCOPE_SELECTED) {
                    emptyList()
                } else {
                    val rest = active.filterNot { it in action.sourceWeeks.toSet() }
                    if (rest.isEmpty()) emptyList() else listOf(course.withWeeks(rest, weekCount))
                }
            }

            is AiAction.UpdateCourse -> {
                if (action.scope != AiAction.SCOPE_SELECTED) {
                    listOf(course.applyPatch(action, periods).let { patched ->
                        action.weeks?.let { patched.withWeeks(it, weekCount) } ?: patched
                    })
                } else {
                    val selected = action.sourceWeeks.toSet()
                    val rest = active.filterNot { it in selected }
                    val moved = action.weeks ?: action.sourceWeeks
                    val out = ArrayList<Course>(2)
                    if (rest.isNotEmpty()) out += course.withWeeks(rest, weekCount)
                    out += course.applyPatch(action, periods)
                        .copy(id = newCourseId())
                        .withWeeks(moved, weekCount)
                    out
                }
            }

            else -> listOf(course)
        }
    }

    /** 局部补丁:只改给了的字段;clearFields 显式清空;节次变了重新钉时刻。 */
    private fun Course.applyPatch(action: AiAction.UpdateCourse, periods: List<Schedule.Period>): Course {
        val clear = action.clearFields.map { it.lowercase() }.toSet()
        var c = this
        action.name?.takeIf { it.isNotBlank() }?.let { c = c.copy(name = it) }
        action.teacher?.let { c = c.copy(teacher = it) }
        action.location?.let { c = c.copy(location = it) }
        action.note?.let { c = c.copy(note = it) }
        action.days?.map { it }.orEmpty().takeIf { it.isNotEmpty() }?.let { days ->
            val mask = days.distinct().filter { it in 1..7 }.fold(0) { acc, d -> acc or (1 shl (d - 1)) }
            c = c.copy(weekdays = mask, weekday = days.first())
        }
        action.startSection?.let { c = c.copy(startPeriod = it) }
        action.endSection?.let { c = c.copy(endPeriod = it.coerceAtLeast(c.startPeriod)) }
        if ("teacher" in clear) c = c.copy(teacher = "")
        if ("location" in clear) c = c.copy(location = "")
        if ("note" in clear) c = c.copy(note = "")
        if ("customtime" in clear) c = c.copy(customStartMinute = null, customEndMinute = null)

        val sectionsChanged = action.startSection != null || action.endSection != null ||
            "customtime" in clear
        return if (sectionsChanged) Schedule.pinCourseTimes(c, periods) else c
    }

    /** 把周次集合写回课程。集合与原记录一致时保持原样,避免把「每周」改成啰嗦的精确列表。 */
    private fun Course.withWeeks(weeks: List<Int>, weekCount: Int): Course {
        val set = weeks.filter { it > 0 }.distinct().sorted()
        if (set.isEmpty()) return this
        val spec = WeekSpec.fromWeeks(set, weekCount)
        return copy(
            weekType = spec.type,
            weekStart = spec.start,
            weekEnd = spec.end,
            weeks = spec.weeks,
        )
    }

    /** 预览用:课程当前的周次文本。 */
    fun weeksLabel(course: Course, settings: AppSettings): String =
        course.weeksText(settings.semesterWeekCount.coerceAtLeast(1))
}

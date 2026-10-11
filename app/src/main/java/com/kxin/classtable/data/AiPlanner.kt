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
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * AI 助手的「上下文拼装」与「动作 → 领域模型」转换。
 *
 * 纯函数,便于推理:上下文给模型看当前课表/日程/作息;动作在用户确认后由这里翻译成
 * [Course] / [AgendaEvent],再交给仓库落库(与表单保存走同一套约定:钉住时刻、周次归一)。
 */
object AiPlanner {

    /** 拼给模型的上下文:今天/周次 + 作息表 + 课表 + 近期日程(有长度上限)。 */
    fun buildContext(
        courses: List<Course>,
        agenda: List<AgendaEvent>,
        settings: AppSettings,
        today: LocalDate = LocalDate.now(),
        maxCourses: Int = 80,
        maxAgenda: Int = 20,
    ): String {
        val periods = Schedule.parsePeriods(settings.periodTimes)
        val week = Schedule.weekOf(today.toEpochDay(), settings.semesterStartDay, settings.semesterWeekCount)

        val sb = StringBuilder()
        sb.append("今天:").append(today)
            .append(" ").append(Dates.weekdayLabel(today.dayOfWeek.value))
            .append(",第 ").append(week).append(" 教学周")
            .append("(学期共 ").append(settings.semesterWeekCount).append(" 周")
        if (settings.semesterStartDay > 0L) sb.append(",开学日 ").append(LocalDate.ofEpochDay(settings.semesterStartDay))
        sb.append(")")

        if (periods.isNotEmpty()) {
            sb.append("\n作息:")
            periods.take(15).forEachIndexed { i, p ->
                sb.append("第").append(i + 1).append("节 ")
                    .append(Schedule.clockText(p.start)).append('-').append(Schedule.clockText(p.end)).append("; ")
            }
        }

        sb.append("\n现有课程(").append(courses.size).append(" 门):")
        if (courses.isEmpty()) sb.append("无")
        courses.take(maxCourses).forEach { c ->
            sb.append("\n- ").append(Dates.weekdayLabel(c.weekday))
                .append(" 第").append(c.startPeriod).append('-').append(c.endPeriod).append("节 ")
                .append(c.name)
            if (c.location.isNotBlank()) sb.append(" @").append(c.location)
            if (c.teacher.isNotBlank()) sb.append(" ").append(c.teacher)
            sb.append(" [").append(c.weeksText()).append("]")
        }

        val zone = ZoneId.systemDefault()
        val upcoming = agenda
            .filter { it.endAt >= today.atStartOfDay(zone).toInstant().toEpochMilli() }
            .sortedBy { it.startAt }
            .take(maxAgenda)
        sb.append("\n近期日程(").append(upcoming.size).append(" 条):")
        if (upcoming.isEmpty()) sb.append("无")
        upcoming.forEach { e ->
            val start = java.time.Instant.ofEpochMilli(e.startAt).atZone(zone).toLocalDateTime()
            sb.append("\n- ").append(start.toLocalDate()).append(' ')
            if (e.allDay) {
                sb.append("全天 ")
            } else {
                sb.append(Schedule.clockText(start.hour * 60 + start.minute)).append(" 起 ")
            }
            sb.append(e.title)
            if (e.location.isNotBlank()) sb.append(" @").append(e.location)
        }
        return sb.toString()
    }

    /** 把建课动作翻成 [Course](已按当前作息钉住时刻、周次归一)。 */
    fun courseDraft(action: AiAction.CreateCourse, settings: AppSettings): Course {
        val periods = Schedule.parsePeriods(settings.periodTimes)
        val spec = WeekSpec.fromWeeks(action.weeks, settings.semesterWeekCount)
        val base = Course(
            id = "ai-${UUID.randomUUID()}",
            name = action.name,
            teacher = action.teacher,
            location = action.location,
            weekday = action.day,
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
            if (endMin <= startMin) return null
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
}

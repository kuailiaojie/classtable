package com.kxin.classtable.data

import android.content.Context
import com.kxin.classtable.domain.Adjustments
import com.kxin.classtable.domain.Schedule
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RFC 5545 iCalendar (.ics) 日历导出管理器。
 *
 * 将全学期课程与自建日程（作业、考试、待办）展开为标准 VEVENT 事件，
 * 支持无缝导入系统日历（如 Apple Calendar、华为、小米、OPPO、Google 等），
 * 并打通 iPhone、iPad、Mac 与智能手表/手环等全生态。
 */
@Singleton
class IcsExporter @Inject constructor(
    private val courseRepository: CourseRepository,
    private val agendaRepository: AgendaRepository,
    private val settingsRepository: SettingsRepository,
    @param:ApplicationContext private val context: Context,
) {
    data class IcsExportResult(
        val eventCount: Int,
        val courseCount: Int,
        val agendaCount: Int,
        val icsText: String,
    ) {
        val totalCount: Int get() = eventCount
    }

    /**
     * 生成符合 RFC 5545 标准的 .ics 日历纯文本。
     */
    suspend fun generateIcsText(): IcsExportResult = withContext(Dispatchers.Default) {
        val courses = courseRepository.getAll()
        val agendas = agendaRepository.getAll()
        val settings = settingsRepository.settings.first()
        val adjustments = Adjustments.decode(settings.scheduleAdjustments)
        val periods = Schedule.parsePeriods(settings.periodTimes)

        val weekCount = settings.semesterWeekCount.coerceIn(1, 30)
        val startDayEpoch = if (settings.semesterStartDay > 0L) {
            settings.semesterStartDay
        } else {
            Schedule.mondayEpochDay(LocalDate.now().toEpochDay())
        }

        val startMonday = LocalDate.ofEpochDay(Schedule.mondayEpochDay(startDayEpoch))
        val endSunday = startMonday.plusWeeks(weekCount.toLong()).minusDays(1)

        val dtFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        val dateOnlyFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val nowStamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .format(Instant.now().atZone(ZoneOffset.UTC))

        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\n")
        sb.append("VERSION:2.0\r\n")
        sb.append("PRODID:-//Yohaku Classtable//CN\r\n")
        sb.append("CALSCALE:GREGORIAN\r\n")
        sb.append("METHOD:PUBLISH\r\n")
        sb.append("X-WR-CALNAME:余课日程\r\n")
        sb.append("X-WR-TIMEZONE:Asia/Shanghai\r\n")

        // 嵌入 Asia/Shanghai VTIMEZONE 保证跨平台（iOS, Android, Watch）时区对齐
        sb.append("BEGIN:VTIMEZONE\r\n")
        sb.append("TZID:Asia/Shanghai\r\n")
        sb.append("X-LIC-LOCATION:Asia/Shanghai\r\n")
        sb.append("BEGIN:STANDARD\r\n")
        sb.append("TZOFFSETFROM:+0800\r\n")
        sb.append("TZOFFSETTO:+0800\r\n")
        sb.append("TZNAME:CST\r\n")
        sb.append("DTSTART:19700101T000000\r\n")
        sb.append("END:STANDARD\r\n")
        sb.append("END:VTIMEZONE\r\n")

        var courseEventCount = 0
        val leadMinutes = settings.notifyLeadMinutes.takeIf { it > 0 } ?: 15

        // 1. 导出课程
        var curDate = startMonday
        while (!curDate.isAfter(endSunday)) {
            val daySchedule = Adjustments.resolve(adjustments, curDate)
            if (!daySchedule.rest) {
                val teaching = Adjustments.teachingDay(adjustments, curDate, startDayEpoch, weekCount)
                if (teaching != null) {
                    val (week, weekday) = teaching
                    for (c in courses) {
                        if (c.isOnWeekday(weekday) && c.isActiveOnWeek(week)) {
                            courseEventCount++
                            val startMin = c.customStartMinute
                                ?: periods.getOrNull(c.startPeriod - 1)?.start
                                ?: 480
                            val endMin = c.customEndMinute
                                ?: periods.getOrNull(c.endPeriod - 1)?.end
                                ?: (startMin + Schedule.PERIOD_LENGTH_MIN)

                            val startDateTime = curDate.atTime(LocalTime.of(startMin / 60, startMin % 60))
                            val endDateTime = curDate.atTime(LocalTime.of(endMin / 60, endMin % 60))

                            val uid = UUID.nameUUIDFromBytes("${c.id}_w${week}_${curDate}".toByteArray()).toString()

                            val descLines = mutableListOf<String>()
                            if (c.teacher.isNotBlank()) descLines.add("教师: ${c.teacher}")
                            if (c.startPeriod > 0) descLines.add("节次: 第${c.startPeriod}节 - 第${c.endPeriod}节")
                            descLines.add("周次: 第${week}周")
                            if (daySchedule.makeup) descLines.add("提示: 调休补课日")
                            if (c.note.isNotBlank()) descLines.add("备注: ${c.note}")

                            sb.append("BEGIN:VEVENT\r\n")
                            sb.append("UID:").append(uid).append("@classtable.kxin.com\r\n")
                            sb.append("DTSTAMP:").append(nowStamp).append("\r\n")
                            sb.append("DTSTART;TZID=Asia/Shanghai:").append(startDateTime.format(dtFormatter)).append("\r\n")
                            sb.append("DTEND;TZID=Asia/Shanghai:").append(endDateTime.format(dtFormatter)).append("\r\n")
                            sb.append("SUMMARY:").append(escapeIcs(c.name)).append("\r\n")
                            if (c.location.isNotBlank()) {
                                sb.append("LOCATION:").append(escapeIcs(c.location)).append("\r\n")
                            }
                            if (descLines.isNotEmpty()) {
                                sb.append("DESCRIPTION:").append(escapeIcs(descLines.joinToString("\n"))).append("\r\n")
                            }
                            sb.append("STATUS:CONFIRMED\r\n")
                            sb.append("TRANSP:OPAQUE\r\n")

                            sb.append("BEGIN:VALARM\r\n")
                            sb.append("ACTION:DISPLAY\r\n")
                            sb.append("DESCRIPTION:").append(escapeIcs("上课提醒: ${c.name}")).append("\r\n")
                            sb.append("TRIGGER:-PT").append(leadMinutes).append("M\r\n")
                            sb.append("END:VALARM\r\n")

                            sb.append("END:VEVENT\r\n")
                        }
                    }
                }
            }
            curDate = curDate.plusDays(1)
        }

        // 2. 导出日程与倒计时（作业、考试、待办）
        var agendaEventCount = 0
        val zone = ZoneId.systemDefault()
        for (a in agendas) {
            agendaEventCount++
            val uid = UUID.nameUUIDFromBytes("agenda_${a.id}".toByteArray()).toString()
            val startLocal = Instant.ofEpochMilli(a.startAt).atZone(zone).toLocalDateTime()
            val endLocal = Instant.ofEpochMilli(a.endAt).atZone(zone).toLocalDateTime()

            sb.append("BEGIN:VEVENT\r\n")
            sb.append("UID:").append(uid).append("@classtable.kxin.com\r\n")
            sb.append("DTSTAMP:").append(nowStamp).append("\r\n")

            if (a.allDay) {
                sb.append("DTSTART;VALUE=DATE:").append(startLocal.toLocalDate().format(dateOnlyFormatter)).append("\r\n")
                sb.append("DTEND;VALUE=DATE:").append(endLocal.toLocalDate().plusDays(1).format(dateOnlyFormatter)).append("\r\n")
            } else {
                sb.append("DTSTART;TZID=Asia/Shanghai:").append(startLocal.format(dtFormatter)).append("\r\n")
                sb.append("DTEND;TZID=Asia/Shanghai:").append(endLocal.format(dtFormatter)).append("\r\n")
            }

            val summaryPrefix = if (a.completed) "[已完成·${a.category.label}]" else "[${a.category.label}]"
            sb.append("SUMMARY:").append(escapeIcs("$summaryPrefix ${a.title}")).append("\r\n")

            if (a.location.isNotBlank()) {
                sb.append("LOCATION:").append(escapeIcs(a.location)).append("\r\n")
            }

            val descList = mutableListOf<String>()
            descList.add("分类: ${a.category.label}")
            if (a.priority.name != "NONE") descList.add("优先级: ${a.priority.label}")
            if (a.note.isNotBlank()) descList.add("备注: ${a.note}")
            if (a.completed) descList.add("状态: 已完成")
            sb.append("DESCRIPTION:").append(escapeIcs(descList.joinToString("\n"))).append("\r\n")

            sb.append("STATUS:").append(if (a.completed) "COMPLETED" else "CONFIRMED").append("\r\n")
            sb.append("TRANSP:OPAQUE\r\n")

            if (a.remindEnabled) {
                sb.append("BEGIN:VALARM\r\n")
                sb.append("ACTION:DISPLAY\r\n")
                sb.append("DESCRIPTION:").append(escapeIcs("日程提醒: ${a.title}")).append("\r\n")
                sb.append("TRIGGER:-PT").append(a.remindLeadMinutes.coerceAtLeast(0)).append("M\r\n")
                sb.append("END:VALARM\r\n")
            }

            sb.append("END:VEVENT\r\n")
        }

        sb.append("END:VCALENDAR\r\n")
        IcsExportResult(courseEventCount + agendaEventCount, courseEventCount, agendaEventCount, sb.toString())
    }

    /**
     * 将 .ics 内容写入指定输出流。
     */
    suspend fun writeIcsToStream(outputStream: OutputStream): IcsExportResult = withContext(Dispatchers.IO) {
        val result = generateIcsText()
        outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(result.icsText) }
        result
    }

    /**
     * 导出为临时共享文件，生成用于系统分享与日历调起的 File 对象。
     */
    suspend fun prepareShareFile(): Pair<File, IcsExportResult> = withContext(Dispatchers.IO) {
        val result = generateIcsText()
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val shareFile = File(exportsDir, "余课课表与日程.ics")
        shareFile.writeText(result.icsText, StandardCharsets.UTF_8)
        shareFile to result
    }

    private fun escapeIcs(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\n", "\\n")
    }
}

package com.kxin.classtable.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.local.CourseDao
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.Course
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 课程提醒排程:把未来 [WINDOW_DAYS] 天内每次上课「开始 - 提前量」时刻设成精确闹钟。
 * 应用启动/开机/课程或设置变化时调用 [rescheduleAll] 重排;删除课程时调用 [cancelCourse]。
 * Android 12+ 未授予精确闹钟权限时降级为 setWindow(±1 分钟)。
 */
@Singleton
class NotificationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: CourseDao,
    private val settingsRepository: SettingsRepository,
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        const val WINDOW_DAYS = 14
        const val ACTION_REMIND = "com.kxin.classtable.ACTION_COURSE_REMIND"
        const val EXTRA_COURSE_ID = "course_id"
        const val EXTRA_START_MINUTE = "start_minute"
        const val EXTRA_COURSE_NAME = "course_name"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_TEACHER = "teacher"
        const val EXTRA_LEAD = "lead_minutes"
        const val EXTRA_EPOCH_DAY = "epoch_day"
    }

    /** 重新排全部提醒(读取当前课程与设置)。 */
    fun rescheduleAll() {
        val settings = runBlocking { settingsRepository.settings.first() }
        val courses = runBlocking { dao.getAll().map { it.toDomain() } }
        if (!settings.notificationsEnabled) {
            cancelAll(courses)
            return
        }
        val lead = settings.notifyLeadMinutes.coerceIn(0, 180)
        val periods = Schedule.parsePeriods(settings.periodTimes)
        val today = LocalDate.now()
        // 先清一段窗口内所有已排闹钟,再重排:课程被删/改时旧闹钟不会残留。
        courses.forEach { cancelCourse(it.id) }
        courses.forEach { course ->
            for (d in 0 until WINDOW_DAYS) {
                val date = today.plusDays(d.toLong())
                val startMinute = Schedule.courseStartMinute(course, periods) ?: continue
                val fireAt = occurrenceFireTime(course, date, settings, periods, lead) ?: continue
                if (fireAt <= System.currentTimeMillis()) continue
                scheduleOne(course, date, fireAt, lead, startMinute)
            }
        }
    }

    private fun cancelAll(courses: List<Course>) {
        courses.forEach { cancelCourse(it.id) }
    }

    /** 取消某课程已排的提醒(删除课程时调用)。 */
    fun cancelCourse(courseId: String) {
        val today = LocalDate.now()
        for (d in 0 until 120) {
            alarmManager.cancel(reminderPendingIntent(courseId, today.plusDays(d.toLong())))
        }
    }

    /** 某课程在指定日期那次课的提醒触发时刻(epoch millis);当天无课返回 null。 */
    private fun occurrenceFireTime(
        course: Course,
        date: LocalDate,
        s: AppSettings,
        periods: List<Schedule.Period>,
        lead: Int,
    ): Long? {
        if (!course.isOnWeekday(date.dayOfWeek.value)) return null
        // 与周视图同源:以开学日所在周的周一为锚推算该日期属于第几周
        val week = Schedule.weekOf(date.toEpochDay(), s.semesterStartDay, s.semesterWeekCount)
        if (!course.isActiveOnWeek(week)) return null
        val startMinute = Schedule.courseStartMinute(course, periods) ?: return null
        val startMillis = date.atTime(startMinute / 60, startMinute % 60)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return startMillis - lead * 60_000L
    }

    private fun scheduleOne(
        course: Course,
        date: LocalDate,
        fireAt: Long,
        lead: Int,
        startMinute: Int,
    ) {
        val intent = reminderIntent(course.id, date).apply {
            putExtra(EXTRA_COURSE_ID, course.id)
            putExtra(EXTRA_START_MINUTE, startMinute)
            putExtra(EXTRA_COURSE_NAME, course.name)
            putExtra(EXTRA_LOCATION, course.location)
            putExtra(EXTRA_TEACHER, course.teacher)
            putExtra(EXTRA_LEAD, lead)
            putExtra(EXTRA_EPOCH_DAY, date.toEpochDay())
        }
        val pi = PendingIntent.getBroadcast(
            context,
            intent.dataString.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, fireAt, 60_000L, pi)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
        }
    }

    private fun reminderIntent(courseId: String, date: LocalDate): Intent =
        Intent(context, CourseReminderReceiver::class.java)
            .setAction(ACTION_REMIND)
            .setData(Uri.parse("course://remind/$courseId/${date.toEpochDay()}"))

    /** 取消用 PendingIntent:extras 不参与匹配,与设置时的 PI 同 key。 */
    private fun reminderPendingIntent(courseId: String, date: LocalDate): PendingIntent {
        val intent = reminderIntent(courseId, date)
        return PendingIntent.getBroadcast(
            context,
            intent.dataString.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

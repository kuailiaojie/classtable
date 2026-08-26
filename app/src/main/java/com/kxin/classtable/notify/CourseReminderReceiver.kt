package com.kxin.classtable.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.local.AppDatabase
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.Course
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId

/**
 * 课程提醒闹钟接收器:触发时**重新读取当堂课程的最新数据**(而不是排程时的旧快照),
 * 保证改课/换教室/调时间后到点显示的是当下信息。
 *
 * Android 16(API 36)启动 [CourseLiveUpdateService] 展示 Live Updates 进度通知;
 * 更老版本或前台服务启动受限时,回退为普通高优先级提醒通知(保证必达)。
 */
class CourseReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val courseId = intent.getStringExtra(NotificationScheduler.EXTRA_COURSE_ID) ?: return
        val epochDay = intent.getLongExtra(NotificationScheduler.EXTRA_EPOCH_DAY, 0L)
        val lead = intent.getIntExtra(NotificationScheduler.EXTRA_LEAD, 10)
        val startMinuteFallback = intent.getIntExtra(NotificationScheduler.EXTRA_START_MINUTE, -1)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.get(context.applicationContext).courseDao()
                val course = dao.getById(courseId)?.toDomain() ?: return@launch
                val periods = Schedule.parsePeriods(
                    runBlocking { SettingsRepository(context.applicationContext).settings.first().periodTimes },
                )
                val startMinute = Schedule.courseStartMinute(course, periods) ?: startMinuteFallback
                if (startMinute < 0) return@launch
                val startMillis = occurrenceMillis(epochDay, startMinute)
                val endMinute = Schedule.courseEndMinute(course, periods)
                    ?: (startMinute + Schedule.PERIOD_LENGTH_MIN)
                val endMillis = occurrenceMillis(epochDay, endMinute)

                if (Build.VERSION.SDK_INT >= 36) {
                    if (!startLiveUpdate(context, course, startMillis, endMillis, lead)) {
                        fallbackReminder(context, course, startMinute, lead)
                    }
                } else {
                    fallbackReminder(context, course, startMinute, lead)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun occurrenceMillis(epochDay: Long, minute: Int): Long =
        LocalDate.ofEpochDay(epochDay)
            .atTime(minute / 60, minute % 60)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    /** 启动 Live Updates 前台服务;受限(后台启动被拒)时返回 false 交由调用方兜底。 */
    private fun startLiveUpdate(
        context: Context,
        course: Course,
        startMillis: Long,
        endMillis: Long,
        lead: Int,
    ): Boolean = runCatching {
        val intent = Intent(context, CourseLiveUpdateService::class.java).apply {
            putExtra(CourseLiveUpdateService.EXTRA_COURSE_ID, course.id)
            putExtra(CourseLiveUpdateService.EXTRA_COURSE_NAME, course.name)
            putExtra(CourseLiveUpdateService.EXTRA_LOCATION, course.location)
            putExtra(CourseLiveUpdateService.EXTRA_TEACHER, course.teacher)
            putExtra(CourseLiveUpdateService.EXTRA_START_MILLIS, startMillis)
            putExtra(CourseLiveUpdateService.EXTRA_END_MILLIS, endMillis)
            putExtra(CourseLiveUpdateService.EXTRA_LEAD, lead)
        }
        context.startForegroundService(intent)
    }.isSuccess

    /** 兜底:直接展示普通高优先级提醒通知。 */
    private fun fallbackReminder(context: Context, course: Course, startMinute: Int, lead: Int) {
        Notifier.showCourseReminder(
            context = context,
            courseId = course.id,
            courseName = course.name,
            startMinute = startMinute,
            location = course.location,
            teacher = course.teacher,
            leadMinutes = lead,
        )
        com.kxin.classtable.data.Analytics.log("course_reminder_shown", "course_id" to course.id)
    }
}

package com.kxin.classtable.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kxin.classtable.data.Analytics
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.local.AppDatabase
import com.kxin.classtable.data.yuketang.YuketangNoticeFilter
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 提醒闹钟接收器,处理三类事件:
 * - [ReminderPlanner.ACTION_REMIND]:到点提醒(标准模式发通知;实时活动模式拉起前台服务)
 * - [ReminderPlanner.ACTION_REFRESH]:次日 00:05 的自续期闹钟,强制重排滚动窗口
 *
 * 触发时**重新读取课程与设置的当前值**(而不是排程时的快照),所以改课/换教室/改作息后
 * 到点显示的是当下信息。已被静音的「课程:日期」直接跳过(重试闹钟也不会再弹)。
 */
class CourseReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderPlanner.withShortWakeLock(appContext, "reminder") {
                    when (action) {
                        ReminderPlanner.ACTION_REFRESH ->
                            planner(appContext).rescheduleAll(force = true)

                        ReminderPlanner.ACTION_REMIND -> handleReminder(appContext, intent)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun planner(context: Context): ReminderPlanner = ReminderPlanner(
        context,
        AppDatabase.get(context).courseDao(),
        SettingsRepository(context),
    )

    private fun handleReminder(context: Context, intent: Intent) {
        val planner = planner(context)
        val muteKey = intent.getStringExtra(ReminderPlanner.EXTRA_MUTE_KEY).orEmpty()
        if (muteKey.isNotBlank() && planner.isMuted(muteKey)) return

        if (intent.getBooleanExtra(ReminderPlanner.EXTRA_TOMORROW, false)) {
            Notifier.showTomorrowReminder(
                context = context,
                courseCount = intent.getIntExtra(ReminderPlanner.EXTRA_TOMORROW_COUNT, 0),
                firstName = intent.getStringExtra(ReminderPlanner.EXTRA_COURSE_NAME).orEmpty(),
                firstLocation = intent.getStringExtra(ReminderPlanner.EXTRA_LOCATION).orEmpty(),
                firstStartMinute = intent.getIntExtra(ReminderPlanner.EXTRA_START_MINUTE, -1),
            )
            return
        }

        val courseId = intent.getStringExtra(ReminderPlanner.EXTRA_COURSE_ID) ?: return
        val startAt = intent.getLongExtra(ReminderPlanner.EXTRA_START_MILLIS, 0L)
        val endAt = intent.getLongExtra(ReminderPlanner.EXTRA_END_MILLIS, 0L)
        val lead = intent.getIntExtra(ReminderPlanner.EXTRA_LEAD, 10)
        val live = intent.getBooleanExtra(ReminderPlanner.EXTRA_LIVE, false)
        val now = System.currentTimeMillis()
        // 已经下课就别再打扰(闹钟投递顺序不保证,重试闹钟可能晚到)
        if (endAt in 1..now) return

        val settings = runBlocking { SettingsRepository(context).settings.first() }
        val periods = Schedule.parsePeriods(settings.periodTimes)
        val course = runBlocking { AppDatabase.get(context).courseDao().getById(courseId)?.toDomain() }
        val name = course?.name ?: intent.getStringExtra(ReminderPlanner.EXTRA_COURSE_NAME).orEmpty()
        val location = course?.location ?: intent.getStringExtra(ReminderPlanner.EXTRA_LOCATION).orEmpty()
        val teacher = course?.teacher.orEmpty()
        val startMinute = course?.let { Schedule.courseStartMinute(it, periods) }
            ?: intent.getIntExtra(ReminderPlanner.EXTRA_START_MINUTE, -1)

        val payload = LiveCourse(
            courseId = courseId,
            name = name,
            location = location,
            startAtMillis = startAt,
            endAtMillis = endAt,
            leadMinutes = lead,
            muteKey = muteKey,
        )

        val started = live && startLiveUpdate(context, payload)
        if (!started) {
            // 标准模式,或实时活动启动失败(后台启动被拒):普通高优先级通知兜底,保证必达
            Notifier.showCourseReminder(
                context = context,
                notificationId = Notifier.reminderId(muteKey.ifBlank { "$courseId:$startAt" }),
                courseId = courseId,
                courseName = name,
                startMinute = startMinute,
                location = location,
                teacher = teacher,
                leadMinutes = lead,
                announcement = latestAnnouncement(context, courseId, settings),
            )
        }
        Analytics.log("course_reminder_shown", "course_id" to courseId)
    }

    /**
     * 该课程最新一条公告的标题(雨课堂),仅当总开关与「提醒内附公告」都开着时取。
     *
     * **只读本地缓存,不联网**:提醒由精确闹钟触发,那一秒必须发出通知,不能等网络;
     * 缓存由后台任务与「立即刷新」维护。雨课堂自己的「上课提醒」在这里被筛掉 —— 我们自己
     * 的提醒已经说了一遍,不必再复述它。
     */
    private fun latestAnnouncement(context: Context, courseId: String, settings: AppSettings): String? {
        if (!settings.yuketangEnabled || !settings.yuketangIncludeInReminder) return null
        return runCatching {
            runBlocking { YuketangNoticeFilter.latestTitleForCourse(AppDatabase.get(context), courseId) }
        }.getOrNull()
    }

    private fun startLiveUpdate(context: Context, payload: LiveCourse): Boolean = runCatching {
        context.startForegroundService(
            Intent(context, CourseLiveUpdateService::class.java)
                .putExtra(ReminderPlanner.EXTRA_COURSE_ID, payload.courseId)
                .putExtra(ReminderPlanner.EXTRA_COURSE_NAME, payload.name)
                .putExtra(ReminderPlanner.EXTRA_LOCATION, payload.location)
                .putExtra(ReminderPlanner.EXTRA_START_MILLIS, payload.startAtMillis)
                .putExtra(ReminderPlanner.EXTRA_END_MILLIS, payload.endAtMillis)
                .putExtra(ReminderPlanner.EXTRA_LEAD, payload.leadMinutes)
                .putExtra(ReminderPlanner.EXTRA_MUTE_KEY, payload.muteKey),
        )
    }.isSuccess
}

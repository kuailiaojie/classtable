package com.kxin.classtable.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kxin.classtable.data.Analytics
import com.kxin.classtable.data.Crash
import com.kxin.classtable.data.yuketang.YuketangNoticeFilter
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AppSettings
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
        val entry = EntryPointAccessors.fromApplication(appContext, NotifyEntryPoint::class.java)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderPlanner.withShortWakeLock(appContext, "reminder") {
                    when (action) {
                        ReminderPlanner.ACTION_REFRESH ->
                            entry.reminderPlanner().rescheduleAll(force = true)

                        ReminderPlanner.ACTION_REMIND -> handleReminder(appContext, intent, entry)

                        ReminderPlanner.ACTION_AGENDA_REMIND ->
                            handleAgendaReminder(appContext, intent, entry)
                    }
                }
            } catch (e: Exception) {
                // 闹钟触发的异常绝不能逃到默认处理器(会带着整个进程崩,且闹钟静默失败)
                Crash.recordException(e, "op" to "reminder_receiver", "action" to action)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 日程提醒:触发时重新读一遍条目(而不是排程时的快照),改过标题 / 地点就显示当下的;
     * 已删除或已结束的条目直接跳过。
     */
    private suspend fun handleAgendaReminder(context: Context, intent: Intent, entry: NotifyEntryPoint) {
        val eventId = intent.getStringExtra(ReminderPlanner.EXTRA_AGENDA_ID) ?: return
        val event = entry.database().agendaDao().getById(eventId)?.toDomain() ?: return
        if (event.isPast()) return

        AgendaNotifier.showAgendaReminder(
            context = context,
            notificationId = AgendaNotifier.agendaReminderId(event.id),
            eventId = event.id,
            title = event.title,
            categoryLabel = event.category.label,
            startAtMillis = event.startAt,
            endAtMillis = event.endAt,
            allDay = event.allDay,
            location = event.location,
        )
        Analytics.log("agenda_reminder_shown", "agenda_id" to eventId)
    }

    private suspend fun handleReminder(context: Context, intent: Intent, entry: NotifyEntryPoint) {
        val planner = entry.reminderPlanner()
        val muteKey = intent.getStringExtra(ReminderPlanner.EXTRA_MUTE_KEY).orEmpty()
        if (muteKey.isNotBlank() && planner.isMuted(muteKey)) return

        if (intent.getBooleanExtra(ReminderPlanner.EXTRA_TOMORROW, false)) {
            CourseNotifier.showTomorrowReminder(
                context = context,
                courseCount = intent.getIntExtra(ReminderPlanner.EXTRA_TOMORROW_COUNT, 0),
                firstName = intent.getStringExtra(ReminderPlanner.EXTRA_COURSE_NAME).orEmpty(),
                firstLocation = intent.getStringExtra(ReminderPlanner.EXTRA_LOCATION).orEmpty(),
                firstStartMinute = intent.getIntExtra(ReminderPlanner.EXTRA_START_MINUTE, -1),
            )
            return
        }

        val courseId = intent.getStringExtra(ReminderPlanner.EXTRA_COURSE_ID) ?: return
        val live = intent.getBooleanExtra(ReminderPlanner.EXTRA_LIVE, false)
        val now = System.currentTimeMillis()

        // 当天的时间分段随闹钟一起带来(排程时按调休后的教学日算好);缺了就跳过
        val payload = intent.toLiveUpdate() ?: return
        val endAt = payload.endAtMillis() ?: return
        // 已经下课 —— 这是排在下课时刻的那个相位边界闹钟(闹钟投递顺序不保证,相变闹钟可能晚到)。
        // 标准提醒直接跳过,不再打扰;实时活动则要靠这一拍把常驻通知收掉 —— 服务内部的 delay 在
        // 息屏 / Doze 时不按墙上时间推进,下课那一拍只能由这个闹钟补齐,否则通知会一直留在通知栏。
        if (endAt <= now) {
            if (live) stopLiveUpdateFor(context, planner, payload)
            return
        }

        val settings = entry.settingsRepository().settings.first()
        val periods = Schedule.parsePeriods(settings.periodTimes)
        val course = entry.database().courseDao().getById(courseId)?.toDomain()
        val name = course?.name?.takeIf { it.isNotBlank() } ?: payload.name
        val location = course?.location ?: payload.location
        val teacher = course?.teacher.orEmpty()
        val startMinute = course?.let { Schedule.courseStartMinute(it, periods) } ?: -1
        val startAt = payload.startAtMillis()
        val leadMinutes = if (startAt != null && now < startAt) {
            ((startAt - now + 59_999L) / 60_000L).toInt()
        } else {
            0
        }
        // 课名/地点取**当下**的值(改课 / 换教室后到点显示的是新的),分段沿用排程时算好的
        val effective = payload.copy(name = name, location = location)

        val started = live && startLiveUpdateService(context, effective)
        if (!started) {
            // 标准模式,或实时活动启动失败(后台启动被拒):普通高优先级通知兜底,保证必达
            CourseNotifier.showCourseReminder(
                context = context,
                notificationId = CourseNotifier.reminderId(muteKey.ifBlank { "$courseId:$endAt" }),
                courseId = courseId,
                courseName = name,
                startMinute = startMinute,
                location = location,
                teacher = teacher,
                leadMinutes = leadMinutes,
                announcement = latestAnnouncement(entry, courseId, settings),
            )
        }
        Analytics.log("course_reminder_shown", "course_id" to courseId)
    }

    /**
     * 下课收尾:收掉正在展示的实时活动。只在「当前展示的确实是这节课」时才动手 ——
     * 两门课时间重叠时,先下课那门的边界闹钟不能把另一门正在上的常驻通知误收掉。
     */
    private fun stopLiveUpdateFor(context: Context, planner: ReminderPlanner, payload: LiveUpdate) {
        val current = planner.restoreLive() ?: return
        if (current.muteKey != payload.muteKey) return
        planner.clearLive()
        LiveUpdateNotifier.cancelLiveUpdate(context)
        context.stopService(Intent(context, CourseLiveUpdateService::class.java))
    }

    /**
     * 该课程最新一条公告的标题(雨课堂),仅当总开关与「提醒内附公告」都开着时取。
     *
     * **只读本地缓存,不联网**:提醒由精确闹钟触发,那一秒必须发出通知,不能等网络;
     * 缓存由后台任务与「立即刷新」维护。雨课堂自己的「上课提醒」在这里被筛掉 —— 我们自己
     * 的提醒已经说了一遍,不必再复述它。
     */
    private suspend fun latestAnnouncement(
        entry: NotifyEntryPoint,
        courseId: String,
        settings: AppSettings,
    ): String? {
        if (!settings.yuketangEnabled || !settings.yuketangIncludeInReminder) return null
        return runCatching {
            YuketangNoticeFilter.latestTitleForCourse(entry.database(), courseId)
        }.getOrNull()
    }
}

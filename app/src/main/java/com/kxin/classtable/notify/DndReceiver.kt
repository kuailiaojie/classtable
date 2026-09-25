package com.kxin.classtable.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.local.AppDatabase
import com.kxin.classtable.domain.Adjustments
import com.kxin.classtable.domain.Schedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

/**
 * 上课自动免打扰的闹钟接收器:
 * - [ReminderPlanner.ACTION_DND_ON]:上课,进入免打扰
 * - [ReminderPlanner.ACTION_DND_OFF]:下课,退出免打扰
 *
 * 退出前先确认**此刻真的没有别的课**:两门课时间重叠时(如 A 8–10、B 9–11),A 下课的闹钟
 * 不能把 B 还在上的免打扰一并关掉。触发时按当前课表重算,不依赖排程时的快照。
 */
class DndReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 与提醒同理:息屏触发时要保证读完课表、写完免打扰状态再让系统睡回去
                ReminderPlanner.withShortWakeLock(appContext, "dnd") {
                    when (action) {
                        ReminderPlanner.ACTION_DND_ON -> DndController.enter(appContext)
                        ReminderPlanner.ACTION_DND_OFF -> {
                            if (!hasOngoingCourse(appContext)) DndController.exit(appContext)
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** 当前时刻是否还有课在上(按调休后的教学日取课)。 */
    private fun hasOngoingCourse(context: Context): Boolean = runCatching {
        val settings = runBlocking { SettingsRepository(context).settings.first() }
        val periods = Schedule.parsePeriods(settings.periodTimes)
        val courses = runBlocking { AppDatabase.get(context).courseDao().getAll().map { it.toDomain() } }
        val today = LocalDate.now()
        val teaching = Adjustments.teachingDay(
            Adjustments.decode(settings.scheduleAdjustments),
            today,
            settings.semesterStartDay,
            settings.semesterWeekCount,
        ) ?: return@runCatching false
        courses.any {
            it.isOnWeekday(teaching.second) &&
                it.isActiveOnWeek(teaching.first) &&
                Schedule.isCourseOngoing(it, periods)
        }
    }.getOrDefault(false)
}

package com.kxin.classtable.notify

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机 / 改时间 / 改时区 / 应用更新 / **精确闹钟权限变化**后重排课程提醒。
 *
 * 闹钟在重启与应用更新后全部失效,必须强制重排(排程窗口只覆盖 8 天,且靠自续期闹钟滚动)。
 * 精确闹钟权限被授予/收回时同样要重排:授权前排下的是窗口闹钟,授权后应换回精确闹钟。
 */
class BootRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val relevant = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == Intent.ACTION_DATE_CHANGED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        if (!relevant) return

        val rebooted = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 只有在**重启**后才丢掉免打扰的接管标记:重启后系统已回到默认档,旧标记留着的话,
                // 一次迟到的「下课」闹钟会把早已过期的档位又写回去。改时间 / 应用更新不该丢 ——
                // 那两种情况下这节课可能还在上,下课闹钟仍需按原样恢复。
                if (rebooted) DndController.forget(appContext)
                ReminderPlanner.withShortWakeLock(appContext, "boot") {
                    ReminderPlanner(
                        appContext,
                        AppDatabase.get(appContext).courseDao(),
                        AppDatabase.get(appContext).agendaDao(),
                        SettingsRepository(appContext),
                    ).rescheduleAll(force = true)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

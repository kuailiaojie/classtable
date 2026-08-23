package com.kxin.classtable.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机 / 改时区 / 改时间 / 应用更新后重排课程提醒。
 * 闹钟在重启后全部失效,必须重排。
 */
class BootRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val relevant = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == Intent.ACTION_DATE_CHANGED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!relevant) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NotificationScheduler(
                    context.applicationContext,
                    AppDatabase.get(context.applicationContext).courseDao(),
                    SettingsRepository(context.applicationContext),
                ).rescheduleAll()
            } finally {
                pendingResult.finish()
            }
        }
    }
}

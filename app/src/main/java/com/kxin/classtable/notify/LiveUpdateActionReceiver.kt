package com.kxin.classtable.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 实时活动通知上的操作按钮:「取消本节课提醒」。
 * 记下静音到这节课结束,并立刻收掉通知与前台服务 —— 之后的重试闹钟也不会再把它拉起来。
 */
class LiveUpdateActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_REMINDER) return
        val appContext = context.applicationContext
        val muteKey = intent.getStringExtra(EXTRA_MUTE_KEY).orEmpty()
        val muteUntil = intent.getLongExtra(EXTRA_MUTE_UNTIL, 0L)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (muteKey.isNotBlank() && muteUntil > 0L) {
                    ReminderPlanner(
                        appContext,
                        AppDatabase.get(appContext).courseDao(),
                        SettingsRepository(appContext),
                    ).mute(muteKey, muteUntil)
                }
                runCatching {
                    NotificationManagerCompat.from(appContext)
                        .cancel(Notifier.LIVE_NOTIFICATION_ID)
                }
                appContext.stopService(Intent(appContext, CourseLiveUpdateService::class.java))
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_CANCEL_REMINDER = "com.kxin.classtable.ACTION_CANCEL_COURSE_REMIND"
        const val EXTRA_MUTE_KEY = "mute_key"
        const val EXTRA_MUTE_UNTIL = "mute_until"
    }
}

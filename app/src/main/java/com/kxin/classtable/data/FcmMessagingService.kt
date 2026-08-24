package com.kxin.classtable.data

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.notify.Notifier
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FCM 推送服务:
 * - onNewToken:令牌刷新 → 同步到 Firestore(登录状态下)。
 * - onMessageReceived:data 消息携带课程字段时,复用本地提醒通知的样式展示;
 *   纯通知 payload 交给系统默认展示。
 */
class FcmMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val entry = EntryPointAccessors.fromApplication(applicationContext, FcmEntryPoint::class.java)
            runCatching { entry.fcmTokens().upload(token) }
            // 令牌刷新 = 系统唤醒了应用,顺带自愈闹钟(国产 ROM 清理后补回来)
            runCatching { entry.notificationScheduler().rescheduleAll() }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // 任何 FCM 消息到达(含营销推送)= 系统唤醒,顺带自愈闹钟
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                EntryPointAccessors.fromApplication(applicationContext, FcmEntryPoint::class.java)
                    .notificationScheduler()
                    .rescheduleAll()
            }
        }

        val data = message.data
        val courseName = data["courseName"]
            ?: message.notification?.title
            ?: return super.onMessageReceived(message)

        val startMinute = data["startMinute"]?.toIntOrNull()
            ?: (data["startTime"]?.let { Schedule.parseClock(it) })
            ?: -1
        Notifier.showCourseReminder(
            context = this,
            courseId = data["courseId"] ?: "",
            courseName = courseName,
            startMinute = startMinute,
            location = data["location"].orEmpty(),
            teacher = data["teacher"].orEmpty(),
            leadMinutes = data["leadMinutes"]?.toIntOrNull() ?: 0,
        )
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface FcmEntryPoint {
        fun fcmTokens(): FcmTokens
        fun notificationScheduler(): com.kxin.classtable.notify.NotificationScheduler
    }
}

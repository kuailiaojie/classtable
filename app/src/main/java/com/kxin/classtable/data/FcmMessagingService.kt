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

        // Live Updates 三类消息:course_reminder 上课提醒 / course_changed 课表变更 / marketing 营销
        val data = message.data
        val handled = when (data["messageType"] ?: data["type"] ?: "course_reminder") {
            "marketing" -> handleMarketing(message)
            "course_changed" -> handleCourseChanged(data)
            else -> handleCourseReminder(data, message)
        }
        // 无课程字段的纯通知消息:交给系统默认展示
        if (!handled) super.onMessageReceived(message)
    }

    /** 营销/活动通知:通用标题+正文。返回 true 表示已处理。 */
    private fun handleMarketing(message: RemoteMessage): Boolean {
        val title = message.notification?.title ?: message.data["title"] ?: return false
        val body = message.notification?.body ?: message.data["body"] ?: ""
        Notifier.showLiveUpdate(this, title, body)
        return true
    }

    /** 课表变更:提示 + 触发云同步与闹钟重排(调课/停课/换教室等)。 */
    private fun handleCourseChanged(data: Map<String, String>): Boolean {
        Notifier.showLiveUpdate(
            this,
            data["title"] ?: "课表已更新",
            data["body"] ?: "课程有变动,请查看最新课表",
        )
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val entry = EntryPointAccessors.fromApplication(applicationContext, FcmEntryPoint::class.java)
            runCatching { entry.syncRepository().syncNow() }
            runCatching { entry.notificationScheduler().rescheduleAll() }
        }
        return true
    }

    /**
     * 上课提醒:与服务端约定,通知 id 与本地闹钟相同(courseId.hashCode()),双通道自动去重。
     * 返回 false 表示没有课程字段,交给系统默认展示。
     */
    private fun handleCourseReminder(data: Map<String, String>, message: RemoteMessage): Boolean {
        val courseName = data["courseName"]
            ?: message.notification?.title
            ?: return false

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
        return true
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface FcmEntryPoint {
        fun fcmTokens(): FcmTokens
        fun syncRepository(): SyncRepository
        fun notificationScheduler(): com.kxin.classtable.notify.NotificationScheduler
    }
}

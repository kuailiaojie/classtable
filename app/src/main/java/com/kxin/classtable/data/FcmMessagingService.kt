package com.kxin.classtable.data

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.notify.Notifier

/**
 * FCM 推送服务:
 * - onNewToken:令牌刷新 → 同步到 Firestore(登录状态下)。
 * - onMessageReceived:data 消息携带课程字段时,复用本地提醒通知的样式展示;
 *   纯通知 payload 交给系统默认展示。
 */
class FcmMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        FcmTokens.upload(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
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
}

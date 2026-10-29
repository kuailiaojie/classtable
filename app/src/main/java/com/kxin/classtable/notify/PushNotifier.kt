package com.kxin.classtable.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.kxin.classtable.MainActivity
import com.kxin.classtable.R
import com.kxin.classtable.data.yuketang.HtmlText

/**
 * 服务端推送类通知:课表动态(调课 / 停课 / 活动)、应用更新、雨课堂新公告。
 *
 * 与本地提醒(课程 / 日程)分开:这些由 FCM 或后台 Worker 触发,不是本机闹钟。
 */
object PushNotifier {
    const val EXTRA_OPEN_UPDATE = "notify_open_update"
    const val EXTRA_OPEN_RAIN_CLASSROOM = "notify_open_rain_classroom"
    private const val NOTIFY_ID_UPDATE = 99001
    private const val NOTIFY_ID_ANNOUNCEMENT = 99004
    private const val REQ_UPDATE_INTENT = 99002
    private const val REQ_ANNOUNCEMENT_INTENT = 99013

    /** 通用推送通知(服务端 FCM):课表变更 / 活动提醒。 */
    fun showLiveUpdate(context: Context, title: String, body: String) {
        if (!NotifyChannels.canPost(context, NotifyChannels.LIVE)) return
        NotifyChannels.ensure(context)
        // 服务端正文可能带 HTML(雨课堂这类内容尤其常见):通知不渲染 HTML,这里再洗一道
        val safeTitle = HtmlText.toPlain(title)
        val safeBody = HtmlText.toPlain(body)
        val notification = NotificationCompat.Builder(context, NotifyChannels.LIVE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(safeTitle)
            .setContentText(safeBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(safeBody))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        postNotification(context, safeTitle.hashCode(), notification)
    }

    /** 后台检查到新版本:一条可点进「检查更新」页的通知。 */
    fun showUpdateAvailable(context: Context, version: String, notes: String) {
        if (!NotifyChannels.canPost(context, NotifyChannels.APP_UPDATE)) return
        NotifyChannels.ensure(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            REQ_UPDATE_INTENT,
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_UPDATE, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = HtmlText.toPlain(notes).trim().ifBlank { "点此查看并下载" }
        val notification = NotificationCompat.Builder(context, NotifyChannels.APP_UPDATE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("发现新版本 v$version")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        postNotification(context, NOTIFY_ID_UPDATE, notification)
    }

    /**
     * 雨课堂有**新公告**:独立渠道 + 独立通知 id,与课程提醒互不覆盖(也不覆盖对方)。
     * 点击进入「设置 → 雨课堂」,那里能看到登录状态与逐课程的公告。
     */
    fun showNewAnnouncement(context: Context, courseName: String, title: String, count: Int) {
        NotifyChannels.ensure(context)
        if (!NotifyChannels.canPost(context, NotifyChannels.ANNOUNCEMENT)) return
        val subject = courseName.ifBlank { "雨课堂" }
        val heading = if (count > 1) "$subject 有 $count 条新公告" else "$subject 有新公告"
        val safeTitle = HtmlText.toPlain(title).trim().ifBlank { "点击查看详情" }
        val notifyId = NOTIFY_ID_ANNOUNCEMENT + (courseName.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) } % 1000)
        val contentIntent = PendingIntent.getActivity(
            context,
            notifyId,
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_RAIN_CLASSROOM, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotifyChannels.ANNOUNCEMENT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(heading)
            .setContentText(safeTitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(safeTitle))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .build()
        postNotification(context, notifyId, notification)
    }
}

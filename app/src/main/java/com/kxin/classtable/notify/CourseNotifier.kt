package com.kxin.classtable.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.kxin.classtable.MainActivity
import com.kxin.classtable.R
import com.kxin.classtable.data.yuketang.HtmlText
import com.kxin.classtable.domain.Schedule

/**
 * 课程提醒通知:每节课的课前提醒 + 前一晚的明日课程预告。
 *
 * 动态内容在**触发时**计算(课程名/开始时间/地点/教师/剩余分钟),点击直达课程详情。
 */
object CourseNotifier {
    const val EXTRA_COURSE_ID = "notify_course_id"
    private const val NOTIFY_ID_TOMORROW = 99003

    /** 课程详情深链用的 PendingIntent 请求码(沿用旧值,改了会变成另一个 PendingIntent)。 */
    private const val REQ_COURSE_CONTENT = 99011

    /** 普通课程提醒通知 id:按「课程 + 日期」区分,不同天的提醒不会互相覆盖。 */
    fun reminderId(key: String): Int = key.hashCode()

    /**
     * 普通课程提醒(标准模式,或实时活动不可用时的兜底)。
     *
     * @param leadMinutes 提前量(0 = 已到上课时间)
     * @param startMinute 课程开始分钟(自 0:00,-1 = 未知)
     * @param announcement 该课程最新一条公告的标题(雨课堂);null/空 = 不附。
     *   只读本地缓存 —— 提醒由精确闹钟触发,那一刻不该联网。
     */
    fun showCourseReminder(
        context: Context,
        notificationId: Int,
        courseId: String,
        courseName: String,
        startMinute: Int,
        location: String,
        teacher: String,
        leadMinutes: Int,
        announcement: String? = null,
        taskHint: String? = null,
    ) {
        if (!NotifyChannels.canPost(context, NotifyChannels.REMINDER)) return
        NotifyChannels.ensure(context)

        val startText = if (startMinute >= 0) Schedule.clockText(startMinute) else ""
        val title = if (leadMinutes > 0) "$courseName 即将开始" else "$courseName 上课了"
        val body = buildString {
            if (leadMinutes > 0) append("还有 $leadMinutes 分钟开始")
            if (startText.isNotEmpty()) append(" · $startText 开始")
            if (location.isNotEmpty()) append("\n$location")
            if (teacher.isNotEmpty()) append(" · $teacher")
            // 公告标题来自缓存,正常已清洗;这里再洗一道,旧数据 / 边界情况也不会漏出标签
            val announcementText = announcement?.let { HtmlText.toPlain(it) }?.takeIf { it.isNotBlank() }
            if (announcementText != null) {
                append("\n最新公告:")
                append(announcementText.take(ANNOUNCEMENT_MAX_CHARS))
            }
            if (!taskHint.isNullOrBlank()) {
                append("\n待办备忘: ")
                append(taskHint.take(ANNOUNCEMENT_MAX_CHARS))
            }
        }

        val notification = NotificationCompat.Builder(context, NotifyChannels.REMINDER)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(courseContentIntent(context, courseId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        postNotification(context, notificationId, notification)
    }

    /** 明日课程预告(前一天晚上):明天门数 + 第一节。 */
    fun showTomorrowReminder(
        context: Context,
        courseCount: Int,
        firstName: String,
        firstLocation: String,
        firstStartMinute: Int,
    ) {
        if (courseCount <= 0) return
        if (!NotifyChannels.canPost(context, NotifyChannels.REMINDER)) return
        NotifyChannels.ensure(context)

        val body = buildString {
            if (firstName.isNotBlank()) {
                append("第一节 $firstName")
                if (firstStartMinute >= 0) append(" · ${Schedule.clockText(firstStartMinute)} 开始")
                if (firstLocation.isNotBlank()) append("\n$firstLocation")
            } else {
                append("点开看看明天要上什么")
            }
        }
        val notification = NotificationCompat.Builder(context, NotifyChannels.REMINDER)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("明天有 $courseCount 门课")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
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
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        postNotification(context, NOTIFY_ID_TOMORROW, notification)
    }

    /** 通知被点击 → 打开应用并直达课程详情(实时活动通知也复用)。 */
    internal fun courseContentIntent(context: Context, courseId: String): PendingIntent = PendingIntent.getActivity(
        context,
        REQ_COURSE_CONTENT,
        Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_COURSE_ID, courseId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

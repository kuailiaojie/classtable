package com.kxin.classtable.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kxin.classtable.MainActivity
import com.kxin.classtable.R
import com.kxin.classtable.domain.Schedule

/**
 * 课程提醒通知:动态内容在触发时计算(课程名/开始时间/地点/教师/剩余分钟)。
 * 通知被点击 → 打开应用并直达课程详情。
 */
object Notifier {
    const val CHANNEL_REMINDER = "course_reminder"
    const val EXTRA_COURSE_ID = "notify_course_id"

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_REMINDER) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_REMINDER,
                        "课程提醒",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "课程开始前的提醒"
                    },
                )
            }
        }
    }

    /**
     * @param leadMinutes 提前量(0 = 已到上课时间)
     * @param startMinute 课程开始分钟(自 0:00,-1 = 未知)
     */
    fun showCourseReminder(
        context: Context,
        courseId: String,
        courseName: String,
        startMinute: Int,
        location: String,
        teacher: String,
        leadMinutes: Int,
    ) {
        if (!hasPermission(context)) return
        ensureChannels(context)

        val startText = if (startMinute >= 0) Schedule.clockText(startMinute) else ""
        val title = if (leadMinutes > 0) "$courseName 即将开始" else "$courseName 上课了"
        val body = buildString {
            if (leadMinutes > 0) append("还有 $leadMinutes 分钟开始")
            if (startText.isNotEmpty()) append(" · $startText 开始")
            if (location.isNotEmpty()) append("\n$location")
            if (teacher.isNotEmpty()) append(" · $teacher")
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            courseId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_COURSE_ID, courseId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(courseId.hashCode(), notification)
        }
    }

    private fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            NotificationManagerCompat.from(context).areNotificationsEnabled()
}

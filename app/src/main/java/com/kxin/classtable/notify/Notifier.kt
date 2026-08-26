package com.kxin.classtable.notify

import android.app.Notification
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
    const val CHANNEL_LIVE = "live_updates"
    const val CHANNEL_COURSE_LIVE = "course_live"
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
            if (nm.getNotificationChannel(CHANNEL_LIVE) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_LIVE,
                        "实时动态",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = "课表变更与活动通知(服务端推送)"
                    },
                )
            }
            if (nm.getNotificationChannel(CHANNEL_COURSE_LIVE) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_COURSE_LIVE,
                        "课程开始提醒",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "课前倒计时与上课状态(Android 16 Live Updates)"
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

    /**
     * Live Updates 通用通知(服务端推送):课表变更 / 活动 / 上课提醒的附加通道。
     * 通知 id 基于内容 hash,同一内容重复推送会覆盖,不会堆积。
     */
    fun showLiveUpdate(context: Context, title: String, body: String) {
        if (!hasPermission(context)) return
        ensureChannels(context)

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(title.hashCode(), notification)
        }
    }

    private fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 进度轨最大刻度(与 ProgressStyle.Segment 长度同基准)。 */
    const val LIVE_PROGRESS_MAX = 1000

    /** 进度轨填充色(默认强调色 #C56473)。 */
    private val RAIL_ACCENT: Int = android.graphics.Color.parseColor("#C56473")

    /**
     * 构建 Android 16 Live Updates 通知(ProgressStyle,状态栏 chip)。
     * 仅 Build.VERSION.SDK_INT >= 36 时调用;返回的通知用于前台服务 startForeground,
     * 由服务在课前到下课期间持续更新([progress] 递增,文案实时变化)。
     *
     * [shortText] 即状态栏小字(如「还有 10 分钟」);[contentTitle]/[contentBody] 用于展开视图。
     * [progress] 为当前进度位置,范围 0..[LIVE_PROGRESS_MAX]。
     */
    fun buildCourseLiveUpdate(
        context: Context,
        contentTitle: String,
        contentBody: String,
        shortText: String,
        progress: Int,
        contentIntent: PendingIntent? = null,
    ): Notification {
        ensureChannels(context)
        val progressStyle = Notification.ProgressStyle()
            .setProgressIndeterminate(false)
            .addProgressSegment(
                Notification.ProgressStyle.Segment(LIVE_PROGRESS_MAX).setColor(RAIL_ACCENT),
            )
            .setProgress(progress.coerceIn(0, LIVE_PROGRESS_MAX))
        val builder = Notification.Builder(context, CHANNEL_COURSE_LIVE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(contentTitle)
            .setContentText(contentBody)
            .setCategory(Notification.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShortCriticalText(shortText)
            .setStyle(progressStyle)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
        return builder.build()
    }
}

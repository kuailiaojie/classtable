package com.kxin.classtable.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kxin.classtable.MainActivity
import com.kxin.classtable.R
import com.kxin.classtable.domain.Schedule

/**
 * 通知构建:渠道、可达性检查、课程提醒与实时活动通知。
 * 动态内容在**触发时**计算(课程名/开始时间/地点/教师/剩余分钟),点击直达课程详情。
 */
object Notifier {
    private const val TAG = "ClasstableNotify"

    /** 提醒里附带的公告标题上限:通知只做「有这回事 + 一句话」,详情去应用里看。 */
    private const val ANNOUNCEMENT_MAX_CHARS = 60

    const val CHANNEL_REMINDER = "course_reminder"
    const val CHANNEL_LIVE = "live_updates"
    const val CHANNEL_COURSE_LIVE = "course_live"
    const val CHANNEL_APP_UPDATE = "app_update"
    const val CHANNEL_ANNOUNCEMENT = "course_announcement"
    const val EXTRA_COURSE_ID = "notify_course_id"
    const val EXTRA_OPEN_UPDATE = "notify_open_update"
    const val EXTRA_OPEN_RAIN_CLASSROOM = "notify_open_rain_classroom"

    /** 实时活动通知固定 id:同一时刻只会有一节课的实时活动。 */
    const val LIVE_NOTIFICATION_ID = 99010
    private const val NOTIFY_ID_UPDATE = 99001
    private const val NOTIFY_ID_TOMORROW = 99003
    private const val NOTIFY_ID_ANNOUNCEMENT = 99004
    private const val REQ_UPDATE_INTENT = 99002
    private const val REQ_LIVE_CONTENT = 99011
    private const val REQ_ANNOUNCEMENT_INTENT = 99013

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_REMINDER) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_REMINDER, "课程提醒", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "课程开始前的提醒" },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_LIVE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_LIVE, "实时动态", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "课表变更与活动通知(服务端推送)" },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_COURSE_LIVE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_COURSE_LIVE, "课程进行中", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "课前倒计时、上课中与课间状态(实时活动)" },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_APP_UPDATE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_APP_UPDATE, "应用更新", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "后台检查到新版本时的提示" },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_ANNOUNCEMENT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ANNOUNCEMENT, "课程公告", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "雨课堂课程有新公告时的提示" },
            )
        }
    }

    /**
     * 是否能把通知送到用户眼前:运行时权限 + 应用通知总开关 + **渠道未被关闭**。
     * 后者以前没查,渠道被关掉时还会以为「发成功」。
     */
    fun canPost(context: Context, channelId: String = CHANNEL_REMINDER): Boolean {
        val permitted = Build.VERSION.SDK_INT < 33 || ContextCompatCheck(context)
        if (!permitted) return false
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(channelId)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    private fun ContextCompatCheck(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

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
    ) {
        if (!canPost(context, CHANNEL_REMINDER)) return
        ensureChannels(context)

        val startText = if (startMinute >= 0) Schedule.clockText(startMinute) else ""
        val title = if (leadMinutes > 0) "$courseName 即将开始" else "$courseName 上课了"
        val body = buildString {
            if (leadMinutes > 0) append("还有 $leadMinutes 分钟开始")
            if (startText.isNotEmpty()) append(" · $startText 开始")
            if (location.isNotEmpty()) append("\n$location")
            if (teacher.isNotEmpty()) append(" · $teacher")
            announcement?.takeIf { it.isNotBlank() }?.let {
                append("\n最新公告:")
                append(it.take(ANNOUNCEMENT_MAX_CHARS))
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(courseContentIntent(context, courseId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        post(context, notificationId, notification)
    }

    /**
     * 实时活动通知:覆盖「课前倒计时 → 上课中 → 下课」,进度条随分钟推进;
     * 通知带「取消本节课提醒」按钮,划掉通知也等同于取消本节提醒(见 delete intent)。
     *
     * 这里用**平台** `Notification.Builder` 而不是 compat 版本:状态栏胶囊(荣耀灵动胶囊 /
     * 小米超级岛 / OPPO 实况通知)认的是 Android 16 的 Live Updates 规范,
     * 需要 `ProgressStyle`(平台 API)+ `setRequestPromotedOngoing` + `setShortCriticalText`,
     * 这三者都只在平台 Builder 上可达。条件见 [CapsuleCompat] 的说明。
     */
    fun buildLiveCourse(
        context: Context,
        courseId: String,
        courseName: String,
        location: String,
        startAtMillis: Long,
        endAtMillis: Long,
        leadMinutes: Int,
        muteKey: String,
        now: Long,
        /** 该课程最新一条公告标题;只进展开文本,不动 bodyText/chipText(胶囊短文案放不下)。 */
        announcement: String? = null,
    ): Notification {
        ensureChannels(context)

        val minutesToStart = minutesBetween(now, startAtMillis)
        val minutesToEnd = minutesBetween(now, endAtMillis)
        val inClass = now >= startAtMillis
        val finished = now >= endAtMillis
        val timeText = "${clock(startAtMillis)}–${clock(endAtMillis)}"
        val placeText = location.ifBlank { "未设置地点" }

        val statusLine = when {
            !inClass && minutesToStart > 0 -> "还有 $minutesToStart 分钟上课"
            !inClass -> "马上就要上课"
            !finished && minutesToEnd > 0 -> "上课中 · 还有 $minutesToEnd 分钟下课"
            else -> "已下课"
        }
        // 胶囊短文案:纯文本、尽量 ≤7 字 —— 状态栏胶囊宽 96dp,放不下就只剩图标
        val chipText = when {
            !inClass && minutesToStart > 0 -> "${minutesToStart}分钟"
            !inClass -> "准备上课"
            !finished && minutesToEnd > 0 -> "${minutesToEnd}分钟"
            else -> "已下课"
        }
        val bodyText = "$statusLine · $timeText"
        val expandedText = buildString {
            append(bodyText)
            if (location.isNotBlank()) append("\n").append(placeText)
            announcement?.takeIf { it.isNotBlank() }?.let {
                append("\n最新公告:").append(it.take(ANNOUNCEMENT_MAX_CHARS))
            }
        }
        val progressing = inClass && !finished

        val builder = Notification.Builder(context, CHANNEL_COURSE_LIVE)
            .setSmallIcon(R.drawable.ic_notify)
            // contentTitle 是不可省的:没有它系统不会提升为实时活动
            .setContentTitle(courseName)
            .setContentText(bodyText)
            .setContentIntent(courseContentIntent(context, courseId))
            .setDeleteIntent(cancelLivePendingIntent(context, muteKey, endAtMillis))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // 固定用默认色:自定义颜色会让部分系统的胶囊强制按普通通知渲染
            .setColor(Notification.COLOR_DEFAULT)
            .setCategory(
                if (progressing) Notification.CATEGORY_PROGRESS else Notification.CATEGORY_EVENT,
            )
            .addAction(
                R.drawable.ic_notify,
                "取消本节课提醒",
                cancelLivePendingIntent(context, muteKey, endAtMillis),
            )

        if (Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(
                if (progressing) {
                    Notification.ProgressStyle()
                        // 单个白色小圆点作为进度点:再叠 Point 会出现两个重叠的进度图示
                        .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.ic_live_dot))
                        .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100)))
                        .setProgress(progressPercent(startAtMillis, endAtMillis, leadMinutes, now))
                } else {
                    Notification.BigTextStyle().bigText(expandedText)
                },
            )
        } else {
            builder.setStyle(Notification.BigTextStyle().bigText(expandedText))
            if (progressing) {
                builder.setProgress(
                    100,
                    progressPercent(startAtMillis, endAtMillis, leadMinutes, now),
                    false,
                )
            }
        }

        CapsuleCompat.requestPromotion(builder, chipText)
        XiaomiIsland.applyTo(
            builder = builder,
            context = context,
            courseName = courseName,
            placeText = placeText,
            statusLabel = statusLine,
            chipText = chipText,
            timeoutMinutes = minutesBetween(now, endAtMillis).coerceAtLeast(1),
        )

        return builder.build().also { notification ->
            CapsuleCompat.logPromotionState(context, notification)
        }
    }

    /** 进度百分比:课前从「提前量」那一刻推进到上课,上课后按本节课时长推进。 */
    private fun progressPercent(startAt: Long, endAt: Long, leadMinutes: Int, now: Long): Int {
        val from = if (now < startAt) {
            startAt - leadMinutes.coerceAtLeast(0) * 60_000L
        } else {
            startAt
        }
        val to = if (now < startAt) startAt else endAt
        val span = (to - from).coerceAtLeast(1L)
        return (((now - from).toFloat() / span) * 100).toInt().coerceIn(0, 100)
    }

    private fun minutesBetween(from: Long, to: Long): Int =
        Math.ceil((to - from) / 60_000.0).toInt()

    private fun clock(millis: Long): String {
        val t = java.time.LocalTime.ofInstant(
            java.time.Instant.ofEpochMilli(millis),
            java.time.ZoneId.systemDefault(),
        )
        return "%02d:%02d".format(t.hour, t.minute)
    }

    /** 通知被点击 → 打开应用并直达课程详情。 */
    fun courseContentIntent(context: Context, courseId: String): PendingIntent = PendingIntent.getActivity(
        context,
        REQ_LIVE_CONTENT,
        Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_COURSE_ID, courseId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 实时活动上的「取消本节课提醒」→ [LiveUpdateActionReceiver] 记录静音并收掉通知。 */
    private fun cancelLivePendingIntent(context: Context, muteKey: String, muteUntil: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            99012,
            Intent(context, LiveUpdateActionReceiver::class.java).apply {
                action = LiveUpdateActionReceiver.ACTION_CANCEL_REMINDER
                putExtra(LiveUpdateActionReceiver.EXTRA_MUTE_KEY, muteKey)
                putExtra(LiveUpdateActionReceiver.EXTRA_MUTE_UNTIL, muteUntil)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 通用推送通知(服务端 FCM):课表变更 / 活动提醒。 */
    fun showLiveUpdate(context: Context, title: String, body: String) {
        if (!canPost(context, CHANNEL_LIVE)) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
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
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        post(context, title.hashCode(), notification)
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
        if (!canPost(context, CHANNEL_REMINDER)) return
        ensureChannels(context)

        val body = buildString {
            if (firstName.isNotBlank()) {
                append("第一节 $firstName")
                if (firstStartMinute >= 0) append(" · ${Schedule.clockText(firstStartMinute)} 开始")
                if (firstLocation.isNotBlank()) append("\n$firstLocation")
            } else {
                append("点开看看明天要上什么")
            }
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER)
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
        post(context, NOTIFY_ID_TOMORROW, notification)
    }

    /** 后台检查到新版本:一条可点进「检查更新」页的通知。 */
    fun showUpdateAvailable(context: Context, version: String, notes: String) {
        if (!canPost(context, CHANNEL_APP_UPDATE)) return
        ensureChannels(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            REQ_UPDATE_INTENT,
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_UPDATE, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = notes.trim().ifBlank { "点此查看并下载" }
        val notification = NotificationCompat.Builder(context, CHANNEL_APP_UPDATE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("发现新版本 v$version")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        post(context, NOTIFY_ID_UPDATE, notification)
    }

    /**
     * 雨课堂有**新公告**:独立渠道 + 独立通知 id,与课程提醒互不覆盖(也不覆盖对方)。
     * 点击进入「设置 → 雨课堂」,那里能看到登录状态与逐课程的公告。
     */
    fun showNewAnnouncement(context: Context, courseName: String, title: String, count: Int) {
        if (!canPost(context, CHANNEL_ANNOUNCEMENT)) return
        ensureChannels(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            REQ_ANNOUNCEMENT_INTENT,
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_RAIN_CLASSROOM, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val subject = courseName.ifBlank { "雨课堂" }
        val heading = if (count > 1) "$subject 有 $count 条新公告" else "$subject 有新公告"
        val notification = NotificationCompat.Builder(context, CHANNEL_ANNOUNCEMENT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(heading)
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .build()
        post(context, NOTIFY_ID_ANNOUNCEMENT, notification)
    }

    /** 统一收口:权限被回收时 notify 会抛 SecurityException,不能让它冒到调用方。 */
    private fun post(context: Context, id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
            .onFailure { Log.w(TAG, "通知发送失败: ${it.javaClass.simpleName}") }
    }
}

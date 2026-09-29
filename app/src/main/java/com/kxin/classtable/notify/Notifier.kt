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
    const val CHANNEL_AGENDA = "agenda_reminder"
    const val EXTRA_COURSE_ID = "notify_course_id"
    const val EXTRA_OPEN_UPDATE = "notify_open_update"
    const val EXTRA_OPEN_RAIN_CLASSROOM = "notify_open_rain_classroom"
    const val EXTRA_AGENDA_ID = "notify_agenda_id"
    const val EXTRA_AGENDA_DAY = "notify_agenda_day"

    /**
     * 实时活动通知的**两个槽位**:同一相位沿用原槽位(分钟刷新原地更新,胶囊不重弹),
     * 跨相位换另一槽位,让胶囊在新状态时重新展开一次。
     */
    const val LIVE_NOTIFICATION_ID = 99010
    const val LIVE_NOTIFICATION_ALTERNATE_ID = 99011
    /** 写在通知 extras 上的「这是哪一帧」,槽位轮换据此判断是不是同一个身份。 */
    const val EXTRA_LIVE_IDENTITY = "classtable.live_update_identity"
    private const val NOTIFY_ID_UPDATE = 99001
    private const val NOTIFY_ID_TOMORROW = 99003
    private const val NOTIFY_ID_ANNOUNCEMENT = 99004
    private const val REQ_UPDATE_INTENT = 99002
    private const val REQ_LIVE_CONTENT = 99011
    private const val REQ_ANNOUNCEMENT_INTENT = 99013
    private const val REQ_AGENDA_CONTENT = 99014

    /** FCM 在「没指定渠道的通知」上自建的兜底渠道(系统里显示为 Miscellaneous)。 */
    private const val FCM_FALLBACK_CHANNEL = "fcm_fallback_notification_channel"

    /**
     * 通知渠道总表:id / 名字 / 重要度 / 说明写在**一处**,新装用户按这个顺序在系统设置里看到。
     *
     * - id 一经发布就不能再改(改了等于多一条新渠道,用户原来的开关与屏蔽全失效),所以这里
     *   只维护已有 id,靠这张表统一顺序与文案。名字 / 说明**每次都以这张表为准重新提交**:
     *   渠道一旦存在,只有再 create 一次才会更新文案(重要度由用户掌控,系统不接受程序改动)。
     * - 顺序按「用户最关心 → 最不关心」排:课前的、正在上的、日程的都在前面,应用更新放最后。
     * - 同名的坑:「课程进行中」是实时活动胶囊(那条常驻通知),不是「课程提醒」,所以名字里
     *   写清「进行中」;服务端推的调课/活动叫「课表动态」,与实时活动区分开。
     */
    private data class Channel(
        val id: String,
        val name: String,
        val importance: Int,
        val description: String,
    )

    private val CHANNELS = listOf(
        Channel(
            CHANNEL_REMINDER,
            "课程提醒",
            NotificationManager.IMPORTANCE_HIGH,
            "每节课开始前的提醒,以及前一晚的明日课程预告",
        ),
        Channel(
            CHANNEL_COURSE_LIVE,
            "课程进行中",
            NotificationManager.IMPORTANCE_HIGH,
            "课前倒计时、上课中与课间状态(状态栏胶囊/实时活动)",
        ),
        Channel(
            CHANNEL_AGENDA,
            "日程提醒",
            NotificationManager.IMPORTANCE_HIGH,
            "日程到点前的提醒",
        ),
        Channel(
            CHANNEL_ANNOUNCEMENT,
            "课程公告",
            NotificationManager.IMPORTANCE_DEFAULT,
            "雨课堂课程有新公告时的提示",
        ),
        Channel(
            CHANNEL_LIVE,
            "课表动态",
            NotificationManager.IMPORTANCE_DEFAULT,
            "服务端推送的调课、停课与活动通知",
        ),
        Channel(
            CHANNEL_APP_UPDATE,
            "应用更新",
            NotificationManager.IMPORTANCE_DEFAULT,
            "后台检查到新版本时的提示",
        ),
    )

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 已存在的渠道也要**再 create 一次**:系统只认「提交时的名字/说明」,渠道一旦建好,
        // 不再 submit 就永远是旧名字。早期版本 live_updates 叫「实时动态」,就是因为这里对
        // 已存在的渠道直接跳过,改名一直没落到老用户身上。
        CHANNELS.forEach { channel ->
            nm.createNotificationChannel(
                NotificationChannel(channel.id, channel.name, channel.importance)
                    .apply { description = channel.description },
            )
        }
        // FCM 的兜底渠道(名字是英文 Miscellaneous):通知型消息没指定渠道时会落到它。
        // 清单里已配 default_notification_channel_id,这里把历史遗留的删掉,设置里不再多一条。
        runCatching { nm.deleteNotificationChannel(FCM_FALLBACK_CHANNEL) }
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
     * 实时活动通知:覆盖「课前倒计时 → 上课中 → 课间中 → 已下课」。
     *
     * **正文只跟状态走,秒级倒计时交给系统的 `when` + Chronometer 自己走**:通知上写死一个
     * 倒计时目标([LiveUpdateStatus.nextTransitionAtMillis]),之后由系统逐秒自己走,我们不为了
     * 刷新分钟数而重发 —— 重发正是国产胶囊「每隔一会儿跳出来一下」的来源。服务只在相位切换时
     * 原地重画一帧。
     *
     * **不挂进度条**。进度条系统不会自己动,要推进就得每隔一会儿重发同一条通知;而国产胶囊
     * (荣耀灵动胶囊 / 小米超级岛)把每次更新都当成「又一条新提醒」再展开一次 —— 这正是「每次
     * 更新时跳出」的根因。去掉进度条后,两次相变之间零重发,胶囊只在真的换状态时才展开。
     *
     * 每次刷新都是同一身份 → 同一槽位的那条通知(原地更新 + `setOnlyAlertOnce`),不重起服务、
     * 不另发一条;只有跨到新相位时才换到另一个槽位,让胶囊在新状态重新展开一次(见 [postLiveUpdate])。
     *
     * 这里用**平台** `Notification.Builder` 而不是 compat 版本:状态栏胶囊认的是
     * Android 16 的 Live Updates 规范,提升请求只在平台 Builder 上可达。
     * 条件见 [CapsuleCompat] 的说明。
     */
    fun buildLiveUpdate(
        context: Context,
        payload: LiveUpdate,
        now: Long,
        /** 该课程最新一条公告标题;只进展开文本。 */
        announcement: String? = null,
    ): Notification {
        ensureChannels(context)

        val status = payload.statusAt(now)
        val placeText = payload.location.ifBlank { "未设置地点" }
        val bodyText = "${status.statusText} · ${payload.timeText}"
        val expandedText = buildString {
            append(bodyText)
            if (payload.location.isNotBlank()) append("\n").append(placeText)
            announcement?.takeIf { it.isNotBlank() }?.let {
                append("\n最新公告:").append(it.take(ANNOUNCEMENT_MAX_CHARS))
            }
        }
        // 倒计时目标:下一个相变(课前 → 上课,课中 → 课间 / 下课,课间 → 上课)。没有目标就不挂计时
        val countdownTo = status.nextTransitionAtMillis

        val builder = Notification.Builder(context, CHANNEL_COURSE_LIVE)
            .setSmallIcon(R.drawable.ic_notify)
            // contentTitle 是不可省的:没有它系统不会提升为实时活动
            .setContentTitle(payload.name)
            .setContentText(bodyText)
            .setContentIntent(courseContentIntent(context, payload.courseId))
            .setDeleteIntent(cancelLivePendingIntent(context, payload.muteKey, payload.endAtMillis() ?: 0L))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // 秒级倒计时交给系统的 when + Chronometer 自己走:写死目标时刻,由系统逐秒更新,
            // 我们不必为了刷新分钟数重发(重发在国产胶囊眼里就是又一条新提醒)
            .setShowWhen(countdownTo != null)
            .setWhen(countdownTo ?: (payload.endAtMillis() ?: now))
            .setUsesChronometer(countdownTo != null)
            .setChronometerCountDown(true)
            // 固定用默认色:自定义颜色会让部分系统的胶囊强制按普通通知渲染
            .setColor(Notification.COLOR_DEFAULT)
            .setCategory(Notification.CATEGORY_EVENT)
            // 不挂进度条:进度条不会自己动,推进它就得重发同一条通知 —— 那正是胶囊每次更新跳出的来源
            .setStyle(Notification.BigTextStyle().bigText(expandedText))
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_notify),
                    "取消本节课提醒",
                    cancelLivePendingIntent(context, payload.muteKey, payload.endAtMillis() ?: 0L),
                ).build(),
            )

        CapsuleCompat.requestPromotion(builder)

        return builder.build().also { notification ->
            notification.extras.putString(EXTRA_LIVE_IDENTITY, payload.notificationIdentityAt(now))
            // 小米超级岛自己不会按秒走,只给它状态 / 地点这类不随时间变的文字
            XiaomiIsland.decorate(
                context,
                notification,
                payload,
                status,
                islandText(payload, status, placeText),
            )
            CapsuleCompat.logPromotionState(context, notification)
        }
    }

    /** 小米超级岛的标题:课前给地点(没有地点就用课名),课中 / 课后给状态。 */
    private fun islandText(payload: LiveUpdate, status: LiveUpdateStatus, placeText: String): String =
        if (status.phase == LiveUpdatePhase.BEFORE_CLASS) {
            if (payload.location.isBlank()) payload.name else placeText
        } else {
            status.statusText
        }

    /**
     * 实时活动通知槽位:同一身份沿用原槽位(分钟刷新原地更新,胶囊不重弹);
     * 身份变了(跨相位)就换到另一个槽位,让胶囊在新状态时重新展开一次。
     */
    internal fun liveUpdateSlot(activeNewestFirst: List<Pair<Int, String?>>, identity: String): Int {
        activeNewestFirst.firstOrNull { it.second == identity }?.let { return it.first }
        return if (activeNewestFirst.firstOrNull()?.first == LIVE_NOTIFICATION_ID) {
            LIVE_NOTIFICATION_ALTERNATE_ID
        } else {
            LIVE_NOTIFICATION_ID
        }
    }

    /**
     * 上屏 / 原地更新实时活动:按身份挑槽位,上新槽后再收掉另一个槽。
     * [attachForeground] 把新槽重新挂回前台服务(槽位变了就得重挂)。
     */
    fun postLiveUpdate(
        context: Context,
        notification: Notification,
        attachForeground: ((Int, Notification) -> Unit)? = null,
    ) {
        val active = context.getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            ?.filter { it.id == LIVE_NOTIFICATION_ID || it.id == LIVE_NOTIFICATION_ALTERNATE_ID }
            ?.sortedByDescending { it.postTime }
            .orEmpty()
        val identity = notification.extras.getString(EXTRA_LIVE_IDENTITY).orEmpty()
        val id = liveUpdateSlot(
            active.map { it.id to it.notification.extras.getString(EXTRA_LIVE_IDENTITY) },
            identity,
        )
        val posted = runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }.isSuccess
        if (!posted) {
            Log.w(TAG, "实时活动上屏失败")
            return
        }
        attachForeground?.invoke(id, notification)
        listOf(LIVE_NOTIFICATION_ID, LIVE_NOTIFICATION_ALTERNATE_ID)
            .filter { it != id }
            .forEach { NotificationManagerCompat.from(context).cancel(it) }
    }

    /** 收掉实时活动的两个槽位(取消本节课提醒 / 下课 / 预览结束)。 */
    fun cancelLiveUpdate(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(LIVE_NOTIFICATION_ID)
        manager.cancel(LIVE_NOTIFICATION_ALTERNATE_ID)
    }

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

    /** 普通日程提醒通知 id:按条目区分,不同条目的提醒不会互相覆盖。 */
    fun agendaReminderId(eventId: String): Int = eventId.hashCode()

    /**
     * 日程到点提醒。
     *
     * 定时的在开始前提醒,正文写起止时刻;全天的在当天固定时刻提醒,正文只写「全天」。
     * 点击直达该日程的编辑页([agendaContentIntent])。
     */
    fun showAgendaReminder(
        context: Context,
        notificationId: Int,
        eventId: String,
        title: String,
        categoryLabel: String,
        startAtMillis: Long,
        endAtMillis: Long,
        allDay: Boolean,
        location: String,
    ) {
        if (!canPost(context, CHANNEL_AGENDA)) return
        ensureChannels(context)

        val heading = if (allDay) "今天:$title" else "$title 即将开始"
        val body = buildString {
            append(if (allDay) "全天" else "${clock(startAtMillis)}–${clock(endAtMillis)}")
            if (categoryLabel.isNotBlank()) append(" · $categoryLabel")
            if (location.isNotBlank()) append("\n$location")
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_AGENDA)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(heading)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(agendaContentIntent(context, eventId, startAtMillis))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        post(context, notificationId, notification)
    }

    /** 通知被点击 → 打开应用并直达该日程的编辑页。 */
    private fun agendaContentIntent(context: Context, eventId: String, startAtMillis: Long): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQ_AGENDA_CONTENT,
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_AGENDA_ID, eventId)
                // 条目所在那天当作编辑页的默认日期(epochDay),与 agenda_form 的 date 参数一致
                putExtra(
                    EXTRA_AGENDA_DAY,
                    java.time.Instant.ofEpochMilli(startAtMillis)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                        .toEpochDay(),
                )
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

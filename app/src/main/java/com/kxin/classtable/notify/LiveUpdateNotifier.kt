package com.kxin.classtable.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.kxin.classtable.R

/**
 * 实时活动(课程进行中的常驻通知)的通知构建与上屏。
 *
 * 覆盖「课前倒计时 → 上课中 → 课间中 → 已下课」。正文只跟状态走,秒级倒计时交给系统的
 * `when` + Chronometer 自己走 —— 不为了刷新分钟数而重发(重发正是国产胶囊「每隔一会儿跳出来
 * 一下」的来源)。服务只在相位切换时原地重画一帧。
 *
 * 用**平台** `Notification.Builder` 而不是 compat 版本:状态栏胶囊认的是 Android 16 的
 * Live Updates 规范,提升请求只在平台 Builder 上可达(见 [CapsuleCompat])。
 */
object LiveUpdateNotifier {
    /**
     * 实时活动通知的**两个槽位**:同一相位沿用原槽位(分钟刷新原地更新,胶囊不重弹),
     * 跨相位换另一槽位,让胶囊在新状态时重新展开一次。
     */
    const val LIVE_NOTIFICATION_ID = 99010
    const val LIVE_NOTIFICATION_ALTERNATE_ID = 99011

    /** 写在通知 extras 上的「这是哪一帧」,槽位轮换据此判断是不是同一个身份。 */
    const val EXTRA_LIVE_IDENTITY = "classtable.live_update_identity"
    private const val REQ_LIVE_CONTENT = 99011

    /**
     * 实时活动通知:每次刷新都是同一身份 → 同一槽位的那条通知(原地更新 + `setOnlyAlertOnce`),
     * 不重起服务、不另发一条;只有跨到新相位时才换到另一个槽位(见 [postLiveUpdate])。
     */
    fun buildLiveUpdate(
        context: Context,
        payload: LiveUpdate,
        now: Long,
        /** 该课程最新一条公告标题;只进展开文本。 */
        announcement: String? = null,
    ): Notification {
        NotifyChannels.ensure(context)

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

        val builder = Notification.Builder(context, NotifyChannels.COURSE_LIVE)
            .setSmallIcon(R.drawable.ic_notify)
            // contentTitle 是不可省的:没有它系统不会提升为实时活动
            .setContentTitle(payload.name)
            .setContentText(bodyText)
            .setContentIntent(CourseNotifier.courseContentIntent(context, payload.courseId))
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
     *
     * 调用方(前台服务)已用 [NotifyChannels.canPost] 检查过 POST_NOTIFICATIONS;这里仍
     * runCatching 兜底,权限被临时撤销也不会崩。
     */
    @SuppressLint("MissingPermission")
    fun postLiveUpdate(
        context: Context,
        notification: Notification,
        attachForeground: ((Int, Notification) -> Unit)? = null,
    ) {
        val active = context.getSystemService(android.app.NotificationManager::class.java)
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
            Log.w(NOTIFY_TAG, "实时活动上屏失败")
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

    /** 实时活动上的「取消本节课提醒」→ [LiveUpdateActionReceiver] 记录静音并收掉通知。 */
    private fun cancelLivePendingIntent(context: Context, muteKey: String, muteUntil: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQ_LIVE_CONTENT,
            Intent(context, LiveUpdateActionReceiver::class.java).apply {
                action = LiveUpdateActionReceiver.ACTION_CANCEL_REMINDER
                putExtra(LiveUpdateActionReceiver.EXTRA_MUTE_KEY, muteKey)
                putExtra(LiveUpdateActionReceiver.EXTRA_MUTE_UNTIL, muteUntil)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

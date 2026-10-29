package com.kxin.classtable.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/**
 * 通知渠道:全部渠道的**总表**与「能不能发」的可达性检查。
 *
 * 从原 `Notifier` 里拆出 —— 渠道注册与权限/开关判断和具体的通知构建是两件事,分开放。
 */
object NotifyChannels {
    const val REMINDER = "course_reminder"
    const val LIVE = "live_updates"
    const val COURSE_LIVE = "course_live"
    const val APP_UPDATE = "app_update"
    const val ANNOUNCEMENT = "course_announcement"
    const val AGENDA = "agenda_reminder"

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
            REMINDER,
            "课程提醒",
            NotificationManager.IMPORTANCE_HIGH,
            "每节课开始前的提醒,以及前一晚的明日课程预告",
        ),
        Channel(
            COURSE_LIVE,
            "课程进行中",
            NotificationManager.IMPORTANCE_HIGH,
            "课前倒计时、上课中与课间状态(状态栏胶囊/实时活动)",
        ),
        Channel(
            AGENDA,
            "日程提醒",
            NotificationManager.IMPORTANCE_HIGH,
            "日程到点前的提醒",
        ),
        Channel(
            ANNOUNCEMENT,
            "课程公告",
            NotificationManager.IMPORTANCE_HIGH,
            "雨课堂课程有新公告时的提示",
        ),
        Channel(
            LIVE,
            "课表动态",
            NotificationManager.IMPORTANCE_DEFAULT,
            "服务端推送的调课、停课与活动通知",
        ),
        Channel(
            APP_UPDATE,
            "应用更新",
            NotificationManager.IMPORTANCE_DEFAULT,
            "后台检查到新版本时的提示",
        ),
    )

    fun ensure(context: Context) {
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
    fun canPost(context: Context, channelId: String = REMINDER): Boolean {
        val permitted = Build.VERSION.SDK_INT < 33 || hasRuntimePermission(context)
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

    private fun hasRuntimePermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}

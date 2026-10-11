package com.kxin.classtable.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.kxin.classtable.MainActivity
import com.kxin.classtable.R

/**
 * 日程到点提醒通知。
 *
 * 定时的在开始前提醒,正文写起止时刻;全天的在当天固定时刻提醒,正文只写「全天」。
 * 点击直达该日程的编辑页。
 */
object AgendaNotifier {
    const val EXTRA_AGENDA_ID = "notify_agenda_id"
    const val EXTRA_AGENDA_DAY = "notify_agenda_day"
    private const val REQ_AGENDA_CONTENT = 99014

    /** 普通日程提醒通知 id:按条目区分,不同条目的提醒不会互相覆盖。 */
    fun agendaReminderId(eventId: String): Int = eventId.hashCode()

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
        if (!NotifyChannels.canPost(context, NotifyChannels.AGENDA)) return
        NotifyChannels.ensure(context)

        val heading = if (allDay) "今天:$title" else "$title 即将开始"
        val body = buildString {
            append(if (allDay) "全天" else "${clock(startAtMillis)}–${clock(endAtMillis)}")
            if (categoryLabel.isNotBlank()) append(" · $categoryLabel")
            if (location.isNotBlank()) append("\n$location")
        }

        val notification = NotificationCompat.Builder(context, NotifyChannels.AGENDA)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(heading)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(agendaContentIntent(context, eventId, startAtMillis))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        postNotification(context, notificationId, notification)
    }

    private fun clock(millis: Long): String {
        val t = java.time.LocalTime.ofInstant(
            java.time.Instant.ofEpochMilli(millis),
            java.time.ZoneId.systemDefault(),
        )
        return "%02d:%02d".format(t.hour, t.minute)
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
}

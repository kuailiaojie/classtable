package com.kxin.classtable.notify

import android.app.Notification
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.kxin.classtable.data.Crash

/** 提醒里附带的公告标题上限:通知只做「有这回事 + 一句话」,详情去应用里看。 */
internal const val ANNOUNCEMENT_MAX_CHARS = 60

internal const val NOTIFY_TAG = "ClasstableNotify"

/** 统一收口:权限被回收时 notify 会抛 SecurityException,不能让它冒到调用方。 */
internal fun postNotification(context: Context, id: Int, notification: Notification) {
    runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
        .onFailure {
            // 通知发不出去是「提醒不弹」类反馈的头号原因,记进 Crashlytics 便于回查
            Log.w(NOTIFY_TAG, "通知发送失败: ${it.javaClass.simpleName}")
            Crash.recordException(it, "op" to "notify_post", "id" to id)
        }
}

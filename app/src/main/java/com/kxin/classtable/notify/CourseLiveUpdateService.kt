package com.kxin.classtable.notify

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.kxin.classtable.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.ceil

/**
 * Android 16 Live Updates 前台服务:在课前提醒时刻启动,持有进度型通知,
 * 覆盖「课前倒计时 → 上课中 → 下课」整个时段,下课自动停止并移除通知。
 * Android 16(API 36)上由系统提升为状态栏 Live Update chip;更老版本由
 * [CourseReminderReceiver] 走普通高优先级通知兜底。
 */
class CourseLiveUpdateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val courseId = intent?.getStringExtra(EXTRA_COURSE_ID) ?: ""
        if (courseId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val name = intent?.getStringExtra(EXTRA_COURSE_NAME) ?: "课程"
        val location = intent?.getStringExtra(EXTRA_LOCATION).orEmpty()
        val teacher = intent?.getStringExtra(EXTRA_TEACHER).orEmpty()
        val startMillis = intent?.getLongExtra(EXTRA_START_MILLIS, 0L) ?: 0L
        val endMillis = intent?.getLongExtra(EXTRA_END_MILLIS, 0L) ?: 0L
        val lead = intent?.getIntExtra(EXTRA_LEAD, 10) ?: 10

        if (startMillis <= 0L || endMillis <= startMillis) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 前台通知与普通提醒同 id(courseId.hashCode()),双通道去重,不叠加。
        val notifId = courseId.hashCode()
        val now = System.currentTimeMillis()
        ServiceCompat.startForeground(
            this,
            notifId,
            liveUpdate(courseId, name, location, teacher, startMillis, endMillis, lead, now),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        scope.launch {
            // 每 30 秒刷新进度与文案;下课 1 分钟后停止前台并移除通知。
            while (true) {
                if (System.currentTimeMillis() >= endMillis + 60_000L) break
                delay(30_000L)
                val n = liveUpdate(
                    courseId, name, location, teacher, startMillis, endMillis, lead,
                    System.currentTimeMillis(),
                )
                NotificationManagerCompat.from(this@CourseLiveUpdateService).notify(notifId, n)
            }
            ServiceCompat.stopForeground(this@CourseLiveUpdateService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun liveUpdate(
        courseId: String,
        name: String,
        location: String,
        teacher: String,
        startMillis: Long,
        endMillis: Long,
        lead: Int,
        now: Long,
    ) = Notifier.buildCourseLiveUpdate(
        context = this,
        contentTitle = titleFor(startMillis, endMillis, name, now),
        contentBody = bodyFor(startMillis, endMillis, name, location, teacher, now),
        shortText = shortFor(startMillis, endMillis, now),
        progress = progressFor(startMillis, lead, now),
        contentIntent = contentIntent(courseId),
    )

    private fun contentIntent(courseId: String): PendingIntent = PendingIntent.getActivity(
        this,
        courseId.hashCode(),
        Intent(this, MainActivity::class.java).apply {
            putExtra(Notifier.EXTRA_COURSE_ID, courseId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun titleFor(startMillis: Long, endMillis: Long, name: String, now: Long): String = when {
        now < startMillis -> "$name 即将开始"
        now < endMillis -> "$name 上课中"
        else -> "$name 已下课"
    }

    private fun bodyFor(
        startMillis: Long,
        endMillis: Long,
        name: String,
        location: String,
        teacher: String,
        now: Long,
    ): String {
        val startText = startClock(startMillis)
        val meta = listOf(location, teacher).filter { it.isNotEmpty() }.joinToString(" · ")
        val status = when {
            now < startMillis -> {
                val remain = ceil((startMillis - now) / 60_000.0).toInt().coerceAtLeast(0)
                if (remain <= 0) "$startText 开始" else "还有 $remain 分钟开始 · $startText"
            }
            now < endMillis -> {
                val remain = ceil((endMillis - now) / 60_000.0).toInt().coerceAtLeast(1)
                "上课中 · 还有 $remain 分钟下课"
            }
            else -> "已下课"
        }
        return buildString {
            append("$name · $status")
            if (meta.isNotEmpty()) append("\n$meta")
        }
    }

    private fun shortFor(startMillis: Long, endMillis: Long, now: Long): String = when {
        now < startMillis -> {
            val remain = ceil((startMillis - now) / 60_000.0).toInt().coerceAtLeast(0)
            if (remain <= 0) "现在上课" else "还有 $remain 分钟"
        }
        now < endMillis -> {
            val remain = ceil((endMillis - now) / 60_000.0).toInt().coerceAtLeast(1)
            "上课中·$remain 分钟"
        }
        else -> "已下课"
    }

    private fun progressFor(startMillis: Long, lead: Int, now: Long): Int {
        val fireAt = startMillis - lead * 60_000L
        val max = Notifier.LIVE_PROGRESS_MAX
        return if (now >= startMillis) {
            max
        } else {
            val span = (startMillis - fireAt).coerceAtLeast(1L)
            ((now - fireAt).toFloat() / span * max).toInt().coerceIn(0, max - 1)
        }
    }

    private fun startClock(millis: Long): String {
        val t = LocalTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        return "%02d:%02d".format(t.hour, t.minute)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_COURSE_ID = "course_id"
        const val EXTRA_COURSE_NAME = "course_name"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_TEACHER = "teacher"
        const val EXTRA_START_MILLIS = "start_millis"
        const val EXTRA_END_MILLIS = "end_millis"
        const val EXTRA_LEAD = "lead_minutes"
    }
}

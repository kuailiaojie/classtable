package com.kxin.classtable.notify

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.local.AppDatabase
import com.kxin.classtable.data.yuketang.YuketangNoticeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 课程进行中的常驻通知(实时活动):从「提前量」时刻起持有通知,覆盖
 * 课前倒计时 → 上课中(还有 N 分钟下课)→ 下课,下课 1 分钟后自动收掉。
 *
 * 与旧实现的差别:
 * - **平台 Notification.Builder + ProgressStyle / 提升请求**:状态栏胶囊(荣耀灵动胶囊、
 *   小米超级岛等)只提升符合 Android 16 Live Updates 规范的通知,细节见 [CapsuleCompat]。
 * - **前台服务类型 specialUse**(而非 dataSync):实时活动是「持续展示进行中状态」,dataSync 在
 *   Android 15+ 有每日时长上限,且胶囊机型按 specialUse 判定(清单里另有子类型说明)。
 * - **按分钟边界对齐刷新**(不用 chronometer:它会被系统替换掉状态栏胶囊的文案)。
 * - **payload 持久化 + 恢复**:进程被杀后服务重启(START_STICKY)仍能接着显示同一节课。
 * - **静音检查**:点了「取消本节课提醒」立即收掉,并且不会因为重试闹钟又冒出来。
 * - 通知权限/渠道被关掉时自停,不做无意义的常驻。
 */
class CourseLiveUpdateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val planner = planner()
        val payload = intent?.toLiveCourse() ?: planner.restoreLive()
        val now = System.currentTimeMillis()

        val usable = payload != null &&
            payload.endAtMillis > now &&
            !planner.isMuted(payload.muteKey, now) &&
            Notifier.canPost(this, Notifier.CHANNEL_COURSE_LIVE)
        if (!usable) {
            Log.i(TAG, "跳过实时活动:无 payload / 已下课 / 已静音 / 无通知权限")
            planner.clearLive()
            stopForegroundNow()
            stopSelf()
            return START_NOT_STICKY
        }

        planner.saveLive(payload!!)
        startForegroundNotification(payload, now)
        startRefreshLoop(planner, payload)
        return START_STICKY
    }

    private fun startForegroundNotification(payload: LiveCourse, now: Long) {
        val notification = Notifier.buildLiveCourse(
            context = this,
            courseId = payload.courseId,
            courseName = payload.name,
            location = payload.location,
            startAtMillis = payload.startAtMillis,
            endAtMillis = payload.endAtMillis,
            leadMinutes = payload.leadMinutes,
            muteKey = payload.muteKey,
            now = now,
            // 首次上屏时取一次;后续由刷新循环更新(公告可能在课前那几分钟里刚发出来)
            announcement = runBlocking { latestAnnouncementTitle(payload.courseId) },
        )
        runCatching {
            ServiceCompat.startForeground(
                this,
                Notifier.LIVE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }.onFailure {
            Log.w(TAG, "startForeground 失败: ${it.javaClass.simpleName}")
            stopSelf()
        }
    }

    private fun startRefreshLoop(planner: ReminderPlanner, payload: LiveCourse) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                // 对齐到下个整分钟:状态栏胶囊上的「N 分钟」跟着墙上时钟跳
                val delayToNextMinute = 60_000L - System.currentTimeMillis() % 60_000L + 150L
                delay(delayToNextMinute.coerceAtLeast(1_000L))

                val now = System.currentTimeMillis()
                if (now >= payload.endAtMillis + 60_000L) break
                if (planner.isMuted(payload.muteKey, now)) break
                if (!Notifier.canPost(this@CourseLiveUpdateService, Notifier.CHANNEL_COURSE_LIVE)) break

                val notification = Notifier.buildLiveCourse(
                    context = this@CourseLiveUpdateService,
                    courseId = payload.courseId,
                    courseName = payload.name,
                    location = payload.location,
                    startAtMillis = payload.startAtMillis,
                    endAtMillis = payload.endAtMillis,
                    leadMinutes = payload.leadMinutes,
                    muteKey = payload.muteKey,
                    now = now,
                    announcement = latestAnnouncementTitle(payload.courseId),
                )
                val posted = runCatching {
                    NotificationManagerCompat.from(this@CourseLiveUpdateService)
                        .notify(Notifier.LIVE_NOTIFICATION_ID, notification)
                }.isSuccess
                if (!posted) break
            }
            finish()
        }
    }

    private fun finish() {
        planner().clearLive()
        stopForegroundNow()
        stopSelf()
    }

    private fun stopForegroundNow() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    private fun planner(): ReminderPlanner = ReminderPlanner(
        applicationContext,
        AppDatabase.get(applicationContext).courseDao(),
        SettingsRepository(applicationContext),
    )

    /**
     * 该课程最新一条公告标题(雨课堂)。只读本地缓存,不联网 —— 实时活动每分钟刷新一次,
     * 不能在这里挂网络请求。开关关掉时直接返回 null;雨课堂自己的「上课提醒」也被筛掉
     * (我们的实时活动已经在说同一件事)。
     */
    private suspend fun latestAnnouncementTitle(courseId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val settings = SettingsRepository(applicationContext).settings.first()
            if (!settings.yuketangEnabled || !settings.yuketangIncludeInReminder) {
                return@runCatching null
            }
            YuketangNoticeFilter.latestTitle(
                AppDatabase.get(applicationContext).announcementDao(),
                courseId,
            )
        }.getOrNull()
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "CourseLiveUpdate"
    }
}

/** 从闹钟 Intent 还原实时活动 payload。 */
internal fun Intent.toLiveCourse(): LiveCourse? {
    val courseId = getStringExtra(ReminderPlanner.EXTRA_COURSE_ID)?.takeIf { it.isNotBlank() } ?: return null
    val startAt = getLongExtra(ReminderPlanner.EXTRA_START_MILLIS, 0L)
    val endAt = getLongExtra(ReminderPlanner.EXTRA_END_MILLIS, 0L)
    if (startAt <= 0L || endAt <= startAt) return null
    return LiveCourse(
        courseId = courseId,
        name = getStringExtra(ReminderPlanner.EXTRA_COURSE_NAME).orEmpty(),
        location = getStringExtra(ReminderPlanner.EXTRA_LOCATION).orEmpty(),
        startAtMillis = startAt,
        endAtMillis = endAt,
        leadMinutes = getIntExtra(ReminderPlanner.EXTRA_LEAD, 10),
        muteKey = getStringExtra(ReminderPlanner.EXTRA_MUTE_KEY).orEmpty(),
    )
}

/** 供外部(如「预览实时活动」)启动服务。 */
fun startLiveCourseService(context: android.content.Context, payload: LiveCourse) {
    runCatching {
        ContextCompat.startForegroundService(
            context,
            Intent(context, CourseLiveUpdateService::class.java)
                .putExtra(ReminderPlanner.EXTRA_COURSE_ID, payload.courseId)
                .putExtra(ReminderPlanner.EXTRA_COURSE_NAME, payload.name)
                .putExtra(ReminderPlanner.EXTRA_LOCATION, payload.location)
                .putExtra(ReminderPlanner.EXTRA_START_MILLIS, payload.startAtMillis)
                .putExtra(ReminderPlanner.EXTRA_END_MILLIS, payload.endAtMillis)
                .putExtra(ReminderPlanner.EXTRA_LEAD, payload.leadMinutes)
                .putExtra(ReminderPlanner.EXTRA_MUTE_KEY, payload.muteKey),
        )
    }
}

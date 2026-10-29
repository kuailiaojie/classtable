package com.kxin.classtable.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kxin.classtable.data.Crash
import com.kxin.classtable.data.yuketang.YuketangNoticeFilter
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 课程进行中的常驻通知(实时活动):从「提前量」时刻起持有通知,覆盖
 * 课前倒计时 → 上课中 → 课间中 → 下课,下课即收。
 *
 * 这套结构参照 SleepDown-Schedule 的实时活动:一节课只有**一个**闹钟,不会再排「提醒后
 * 1/3/5 分钟」那种没有新状态的重发;课中也不挂进度条 —— 进度条不会自己动,推进它就得每分钟
 * 重发一次。秒级倒计时交给系统的 `when` + Chronometer 自己走,所以两次相变之间**零重发**,
 * 只在相位切换时换槽上屏 —— 都是同一条通知(同一身份 → 同一槽位)、同一个服务实例,不重起前台服务、
 * 也不另发一条(通知本身 `setOnlyAlertOnce`,见 [LiveUpdateNotifier.buildLiveUpdate])。
 *
 * - **平台 Notification.Builder + 提升请求**:状态栏胶囊只提升符合
 *   Android 16 Live Updates 规范的通知,细节见 [CapsuleCompat]。
 * - **前台服务类型 specialUse**(而非 dataSync):实时活动是「持续展示进行中状态」,dataSync 在
 *   Android 15+ 有每日时长上限,且胶囊机型按 specialUse 判定(清单里另有子类型说明)。
 * - **按相变精确醒来**:[LiveUpdate.nextRefreshAtMillis] 给出下一个该重画的时刻(相位边界),
 *   两次相变之间不重画(重画在国产胶囊眼里可能被当成新提醒)。息屏时另由边界闹钟兜底(见 [ReminderPlanner])。
 * - **payload 持久化 + 恢复**:进程被杀后服务重启(START_STICKY)仍能接着显示同一节课。
 * - **静音检查**:点了「取消本节课提醒」立即收掉,重试与重启后依然生效。
 * - 通知权限/渠道被关掉时自停,不做无意义的常驻。
 */
class CourseLiveUpdateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshJob: Job? = null

    /** 本进程当前已经上屏那一帧的身份:同一次服务里重复收到同一帧不再重发通知。 */
    private var onScreenIdentity: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val planner = planner()
        val payload = intent?.toLiveUpdate() ?: planner.restoreLive()
        val now = System.currentTimeMillis()

        val usable = payload != null &&
            !payload.shouldStop(now) &&
            !planner.isMuted(payload.muteKey, now) &&
            NotifyChannels.canPost(this, NotifyChannels.COURSE_LIVE)
        if (!usable) {
            Log.i(TAG, "跳过实时活动:无 payload / 已下课 / 已静音 / 无通知权限")
            // 只清这节课自己的 payload:课表重叠时,正在上的可能是另一门课,
            // 它的 payload 与通知不能跟着被清掉(否则下课那一拍找不到人收通知)
            val stored = planner.restoreLive()
            if (stored == null || stored.muteKey == (payload?.muteKey)) {
                planner.clearLive()
            }
            stopForegroundNow()
            LiveUpdateNotifier.cancelLiveUpdate(this)
            stopSelf()
            return START_NOT_STICKY
        }

        planner.saveLive(payload!!)
        // 已经在屏幕上的同一帧不再重发(重发在胶囊眼里就是又一条新提醒);进程被杀后重启时
        // onScreenIdentity 是空的,那次会正常上屏
        val identity = payload.notificationIdentityAt(now)
        if (onScreenIdentity != identity) {
            onScreenIdentity = identity
            // 上屏放到协程里:取「最新公告」要读 DataStore + Room,不能在 onStartCommand 的主线程阻塞
            scope.launch { startForegroundNotification(payload, now) }
        }
        startRefreshLoop(planner, payload)
        return START_STICKY
    }

    private suspend fun startForegroundNotification(payload: LiveUpdate, now: Long) {
        val notification = LiveUpdateNotifier.buildLiveUpdate(
            context = this,
            payload = payload,
            now = now,
            // 首次上屏时取一次;后续由刷新循环更新(公告可能在课前那几分钟里刚发出来)
            announcement = latestAnnouncementTitle(payload.courseId),
        )
        val attached = runCatching {
            LiveUpdateNotifier.postLiveUpdate(this, notification) { id, value -> attachForeground(id, value) }
        }
        if (attached.isFailure) {
            // 挂前台失败:通知可能已经上屏但没有服务兜着,必须一并收掉,否则留下一条用户划不掉的常驻通知
            Log.w(TAG, "startForeground 失败: ${attached.exceptionOrNull()?.javaClass?.simpleName}")
            Crash.recordException(
                attached.exceptionOrNull() ?: IllegalStateException("startForeground failed"),
                "op" to "live_update_start_foreground",
            )
            LiveUpdateNotifier.cancelLiveUpdate(this)
            stopSelf()
        }
    }

    private fun attachForeground(id: Int, notification: android.app.Notification) {
        ServiceCompat.startForeground(
            this,
            id,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    /**
     * 睡到「下一次需要重画」的那一刻再醒 —— 也就是下一个相位边界。
     *
     * 每次唤醒都先判断该不该继续(下课 / 静音 / 权限被关),再原地更新同一条通知的一部分:
     * 同一个身份 → 同一个槽位,只有跨相位才会换槽(见 [LiveUpdateNotifier.postLiveUpdate])。
     * 其余时间静默等待,不产生任何更新。
     */
    private fun startRefreshLoop(planner: ReminderPlanner, payload: LiveUpdate) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            // onStartCommand 已经上屏了第一帧,这里从第二帧开始
            var firstFrame = true
            while (isActive) {
                val now = System.currentTimeMillis()
                if (payload.shouldStop(now)) break
                if (planner.isMuted(payload.muteKey, now)) break
                if (!NotifyChannels.canPost(this@CourseLiveUpdateService, NotifyChannels.COURSE_LIVE)) break

                if (!firstFrame) {
                    val renderedAt = System.currentTimeMillis()
                    val notification = LiveUpdateNotifier.buildLiveUpdate(
                        context = this@CourseLiveUpdateService,
                        payload = payload,
                        now = renderedAt,
                        announcement = latestAnnouncementTitle(payload.courseId),
                    )
                    val posted = runCatching {
                        LiveUpdateNotifier.postLiveUpdate(this@CourseLiveUpdateService, notification) { id, value ->
                            attachForeground(id, value)
                        }
                    }.isSuccess
                    if (!posted) break
                    onScreenIdentity = payload.notificationIdentityAt(renderedAt)
                }
                firstFrame = false

                val nextRefresh = payload.nextRefreshAtMillis()
                    ?: payload.nextRefreshAtMillis(System.currentTimeMillis())
                    ?: break
                delay((nextRefresh - System.currentTimeMillis()).coerceAtLeast(1L))
            }
            finish()
        }
    }

    private fun finish() {
        onScreenIdentity = null
        planner().clearLive()
        stopForegroundNow()
        LiveUpdateNotifier.cancelLiveUpdate(this)
        stopSelf()
    }

    private fun stopForegroundNow() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    private fun planner(): ReminderPlanner =
        EntryPointAccessors.fromApplication(applicationContext, NotifyEntryPoint::class.java)
            .reminderPlanner()

    /**
     * 该课程最新一条公告标题(雨课堂)。只读本地缓存,不联网 —— 实时活动按相变时刻醒来刷新,
     * 不能在这里挂网络请求。开关关掉时直接返回 null;雨课堂自己的「上课提醒」也被筛掉
     * (我们的实时活动已经在说同一件事)。
     */
    private suspend fun latestAnnouncementTitle(courseId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val entry = EntryPointAccessors.fromApplication(applicationContext, NotifyEntryPoint::class.java)
            val settings = entry.settingsRepository().settings.first()
            if (!settings.yuketangEnabled || !settings.yuketangIncludeInReminder) {
                return@runCatching null
            }
            YuketangNoticeFilter.latestTitleForCourse(entry.database(), courseId)
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
internal fun Intent.toLiveUpdate(): LiveUpdate? {
    val courseId = getStringExtra(ReminderPlanner.EXTRA_COURSE_ID)?.takeIf { it.isNotBlank() } ?: return null
    val segments = decodeLiveSegments(getStringExtra(ReminderPlanner.EXTRA_SEGMENTS).orEmpty())
    if (segments.isEmpty()) return null
    return LiveUpdate(
        courseId = courseId,
        name = getStringExtra(ReminderPlanner.EXTRA_COURSE_NAME).orEmpty(),
        location = getStringExtra(ReminderPlanner.EXTRA_LOCATION).orEmpty(),
        timeText = getStringExtra(ReminderPlanner.EXTRA_TIME_TEXT).orEmpty(),
        muteKey = getStringExtra(ReminderPlanner.EXTRA_MUTE_KEY).orEmpty(),
        segments = segments,
    )
}

/** 供外部(如「预览实时活动」、提醒接收器)启动服务。返回是否成功拉起(后台启动可能被拒)。 */
fun startLiveUpdateService(context: Context, payload: LiveUpdate): Boolean = runCatching {
    ContextCompat.startForegroundService(
        context,
        Intent(context, CourseLiveUpdateService::class.java)
            .putExtra(ReminderPlanner.EXTRA_COURSE_ID, payload.courseId)
            .putExtra(ReminderPlanner.EXTRA_COURSE_NAME, payload.name)
            .putExtra(ReminderPlanner.EXTRA_LOCATION, payload.location)
            .putExtra(ReminderPlanner.EXTRA_TIME_TEXT, payload.timeText)
            .putExtra(ReminderPlanner.EXTRA_MUTE_KEY, payload.muteKey)
            .putExtra(ReminderPlanner.EXTRA_SEGMENTS, encodeLiveSegments(payload.segments)),
    )
}.isSuccess

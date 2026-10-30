package com.kxin.classtable

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.webkit.WebView
import com.kxin.classtable.domain.model.NotifyMode
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.installations.FirebaseInstallations
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kxin.classtable.notify.NotifyChannels
import com.kxin.classtable.notify.ReminderSelfHealWorker
import com.kxin.classtable.data.Analytics
import com.kxin.classtable.data.AgendaRepository
import com.kxin.classtable.data.AppCheck
import com.kxin.classtable.data.AuthRepository
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.Crash
import com.kxin.classtable.data.FcmTokens
import com.kxin.classtable.data.RemoteConfig
import com.kxin.classtable.data.RomHelper
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.SyncRepository
import com.kxin.classtable.data.UpdateCheckWorker
import com.kxin.classtable.data.yuketang.AnnouncementCheckWorker
import com.kxin.classtable.data.yuketang.YuketangRepository
import com.kxin.classtable.di.ApplicationScope
import com.kxin.classtable.icon.AppIcon
import com.kxin.classtable.icon.applyAppIcon
import com.kxin.classtable.notify.ReminderPlanner
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ClasstableApp : Application() {

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var syncRepository: SyncRepository

    @Inject
    lateinit var courseRepository: CourseRepository

    @Inject
    lateinit var agendaRepository: AgendaRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var reminderPlanner: ReminderPlanner

    @Inject
    lateinit var fcmTokens: FcmTokens

    @Inject
    lateinit var yuketangRepository: YuketangRepository

    /** 应用级协程作用域,由 Hilt 提供(与各仓库共用同一个,活到进程结束)。 */
    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    private var startedActivityCount = 0
    private var pendingAppIconIndex: Int? = null

    /**
     * 上报推送标识。FCM 已从「注册令牌」转向 Firebase Installation ID(FID):旧的
     * `getToken()` / `onNewToken()` 都已废弃,改用 FID;FID 可直接当作定向投递的令牌用。
     */
    private fun uploadPushRegistration() {
        FirebaseInstallations.getInstance().id.addOnSuccessListener { fid ->
            scope.launch { fcmTokens.upload(fid) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 调试包开放 WebView 远程调试(全局静态生效一次,避免在 WebView 创建时重复调用)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                // 退至后台时,若有待生效的图标切换,此时执行 alias 切换不会打断前台任务或引起桌面异常退出
                if (startedActivityCount == 0 && pendingAppIconIndex != null) {
                    val target = pendingAppIconIndex!!
                    pendingAppIconIndex = null
                    applyAppIcon(this@ClasstableApp, AppIcon.of(target))
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // App Check 必须先于任何 Firebase 调用安装 provider(给反代请求附 attestation)
        AppCheck.init(this)
        Analytics.init(this)
        RemoteConfig.init(this)

        // Crashlytics:崩溃自动上报;附带版本信息便于定位。无网络时本地缓存,恢复后补传。
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("version_name", BuildConfig.VERSION_NAME)
                setCustomKey("version_code", BuildConfig.VERSION_CODE)
            }
        }

        // Remote Config 后台拉一次;失败/大陆取不到就用代码默认值,不影响任何功能
        scope.launch { RemoteConfig.fetch() }

        // 通知渠道与三组定时任务都放到后台线程:建渠道是系统 IPC,WorkManager 首次调用会初始化
        // 自己的 SQLite 库并各写一次 enqueue —— 放在 onCreate 主线程会直接计入冷启动耗时,
        // 而首帧之前完全不需要它们(渠道只要在任何通知真正上屏前建好即可)。
        scope.launch {
            // 通知渠道在启动时就建齐:FCM 的系统兜底展示(通知型消息)也才找得到「课表动态」,
            // 不会退回 SDK 自建的 Miscellaneous;顺带让老用户看到改名后的渠道文案。
            NotifyChannels.ensure(this@ClasstableApp)

            val workManager = WorkManager.getInstance(this@ClasstableApp)
            // 提醒自愈:每 12 小时重排全部闹钟(国产 ROM 可能清掉精确闹钟,靠它补回来)。
            workManager.enqueueUniquePeriodicWork(
                "reminder_self_heal",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReminderSelfHealWorker>(12, java.util.concurrent.TimeUnit.HOURS).build(),
            )

            // 自动检查更新:每天一次(仅在联网时跑);开关与节流在 UpdateRepository 里判断。
            workManager.enqueueUniquePeriodicWork(
                "update_check",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, java.util.concurrent.TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build(),
            )

            // 雨课堂公告:每小时拉一次(仅在联网时跑);开关与登录态在 Worker 里判断,
            // 拉取只为把公告缓存刷新到本机,有新公告时才另发通知。与课程提醒互不相干。
            AnnouncementCheckWorker.schedulePeriodic(this@ClasstableApp)
            if (yuketangRepository.isLoggedIn()) {
                AnnouncementCheckWorker.triggerOneTime(this@ClasstableApp)
            }
        }

        // 登录后自动触发同步(拉远端 → 合并 → 推本地),失败自动重试;并同步 FCM 令牌
        scope.launch {
            authRepository.currentUser.collect { user ->
                Analytics.setUserId(user?.uid)
                Crash.setUserId(user?.uid)
                Crash.log(if (user != null) "signed in" else "signed out")
                if (user != null) {
                    syncWithRetry()
                    uploadPushRegistration()
                }
            }
        }

        // 用户属性:主题 / 通知形态 / ROM,供分析分段用;值变了才写
        scope.launch {
            settingsRepository.settings
                .map { Triple(it.themeMode.name, it.notifyMode, it.notificationsEnabled) }
                .distinctUntilChanged()
                .collect { (theme, notifyMode, notifications) ->
                    Analytics.setUserProperty("theme_mode", theme)
                    Analytics.setUserProperty("notify_mode", notifyMode)
                    Analytics.setUserProperty("notifications_enabled", notifications.toString())
                }
        }
        scope.launch { Analytics.setUserProperty("rom_type", RomHelper.detect().name) }

        // 网络恢复时自动补一次同步(直连抖动/切换 WiFi 流量后不用手动操作)
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (authRepository.isSignedIn) {
                    scope.launch { syncWithRetry() }
                }
            }
        })

        // 课程 / 日程 / 设置(作息/学期/通知开关/提前量/提醒模式)变化 → 重排提醒。
        // 排程按「签名」幂等,且仅监听与排程相关的配置子集,过滤无关设置(如检查更新、图标、雨课堂拉取时间戳等)的高频触发。
        val scheduleSettingsFlow = settingsRepository.settings
            .map { s ->
                ScheduleRelevantSettings(
                    semesterStartDay = s.semesterStartDay,
                    semesterWeekCount = s.semesterWeekCount,
                    periodTimes = s.periodTimes,
                    scheduleAdjustments = s.scheduleAdjustments,
                    notificationsEnabled = s.notificationsEnabled,
                    notifyLeadMinutes = s.notifyLeadMinutes,
                    notifyMode = s.notifyMode,
                    tomorrowReminderEnabled = s.tomorrowReminderEnabled,
                    tomorrowReminderTime = s.tomorrowReminderTime,
                    agendaReminderEnabled = s.agendaReminderEnabled,
                    agendaAllDayRemindTime = s.agendaAllDayRemindTime,
                )
            }
            .distinctUntilChanged()

        scope.launch {
            combine(
                courseRepository.observeAll(),
                agendaRepository.observeAll(),
                scheduleSettingsFlow,
            ) { _, _, _ -> Unit }.collect {
                reminderPlanner.rescheduleAll()
            }
        }

        // 桌面图标:前台切换 activity-alias 在部分 ROM 上会导致应用闪退回桌面。
        // 若当前有 Activity 在前台,暂存目标索引,延迟至应用退入后台(onActivityStopped 计数归零)时再切换。
        scope.launch {
            settingsRepository.settings
                .map { it.appIconIndex }
                .distinctUntilChanged()
                .collect { newIndex ->
                    if (startedActivityCount == 0) {
                        applyAppIcon(this@ClasstableApp, AppIcon.of(newIndex))
                    } else {
                        pendingAppIconIndex = newIndex
                    }
                }
        }
    }

    /** Firestore 直连抖动时自动重试,最多 3 次。 */
    private suspend fun syncWithRetry() {
        repeat(3) { attempt ->
            Crash.log("sync attempt ${attempt + 1}")
            if (syncRepository.syncNow().isSuccess) return
            if (attempt < 2) delay(5_000)
        }
    }
}


/** 仅提取与课程及日程提醒相关的配置子集,避免无关设置字段变动引发重复数据库全量重查。 */
private data class ScheduleRelevantSettings(
    val semesterStartDay: Long,
    val semesterWeekCount: Int,
    val periodTimes: String,
    val scheduleAdjustments: String,
    val notificationsEnabled: Boolean,
    val notifyLeadMinutes: Int,
    val notifyMode: String,
    val tomorrowReminderEnabled: Boolean,
    val tomorrowReminderTime: String,
    val agendaReminderEnabled: Boolean,
    val agendaAllDayRemindTime: String,
)

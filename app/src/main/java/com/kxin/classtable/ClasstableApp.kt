package com.kxin.classtable

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.messaging.FirebaseMessaging
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kxin.classtable.notify.ReminderSelfHealWorker
import com.kxin.classtable.data.Analytics
import com.kxin.classtable.data.AuthRepository
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.FcmTokens
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.SyncRepository
import com.kxin.classtable.data.UpdateCheckWorker
import com.kxin.classtable.data.yuketang.AnnouncementCheckWorker
import com.kxin.classtable.icon.AppIcon
import com.kxin.classtable.icon.applyAppIcon
import com.kxin.classtable.notify.ReminderPlanner
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var reminderPlanner: ReminderPlanner

    @Inject
    lateinit var fcmTokens: FcmTokens

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Analytics.init(this)

        // Crashlytics:崩溃自动上报;附带版本信息便于定位。无网络时本地缓存,恢复后补传。
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("version_name", BuildConfig.VERSION_NAME)
                setCustomKey("version_code", BuildConfig.VERSION_CODE)
            }
        }

        // 提醒自愈:每 12 小时重排全部闹钟(国产 ROM 可能清掉精确闹钟,靠它补回来)。
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "reminder_self_heal",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderSelfHealWorker>(12, java.util.concurrent.TimeUnit.HOURS).build(),
        )

        // 自动检查更新:每天一次(仅在联网时跑);开关与节流在 UpdateRepository 里判断。
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
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
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "announcement_check",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AnnouncementCheckWorker>(1, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build(),
        )

        // 登录后自动触发同步(拉远端 → 合并 → 推本地),失败自动重试;并同步 FCM 令牌
        scope.launch {
            authRepository.currentUser.collect { user ->
                if (user != null) {
                    syncWithRetry()
                    FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                        scope.launch { fcmTokens.upload(token) }
                    }
                }
            }
        }

        // 网络恢复时自动补一次同步(直连抖动/切换 WiFi 流量后不用手动操作)
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (authRepository.isSignedIn) {
                    scope.launch { syncWithRetry() }
                }
            }
        })

        // 课程或设置(作息/学期/通知开关/提前量/提醒模式)变化 → 重排课程提醒。
        // 排程按「签名」幂等:内容没变时直接返回,不再每次发射都全量取消+重排。
        scope.launch {
            combine(
                courseRepository.observeAll(),
                settingsRepository.settings,
            ) { _, _ -> Unit }.collect {
                reminderPlanner.rescheduleAll()
            }
        }

        // 桌面图标:选中的那张(或轮播换到的那张)一变,就把启用的 activity-alias 切过去。
        // 启动时也会跑一次 —— 等于把 alias 与设置对齐,升级 / 重装后仍然一致。
        scope.launch {
            settingsRepository.settings
                .map { it.appIconIndex }
                .distinctUntilChanged()
                .collect { applyAppIcon(this@ClasstableApp, AppIcon.of(it)) }
        }
    }

    /** Firestore 直连抖动时自动重试,最多 3 次。 */
    private suspend fun syncWithRetry() {
        repeat(3) { attempt ->
            if (syncRepository.syncNow().isSuccess) return
            if (attempt < 2) delay(5_000)
        }
    }
}

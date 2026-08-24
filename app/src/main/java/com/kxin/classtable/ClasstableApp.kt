package com.kxin.classtable

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.google.firebase.messaging.FirebaseMessaging
import com.kxin.classtable.data.Analytics
import com.kxin.classtable.data.AuthRepository
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.FcmTokens
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.SyncRepository
import com.kxin.classtable.notify.NotificationScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
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
    lateinit var notificationScheduler: NotificationScheduler

    @Inject
    lateinit var fcmTokens: FcmTokens

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Analytics.init(this)

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

        // 课程或设置(作息/学期/通知开关/提前量)变化 → 重排课程提醒
        scope.launch {
            combine(
                courseRepository.observeAll(),
                settingsRepository.settings,
            ) { _, _ -> Unit }.collect {
                notificationScheduler.rescheduleAll()
            }
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

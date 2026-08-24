package com.kxin.classtable.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 提醒自愈兜底:周期运行(每 12 小时)无条件重排全部闹钟。
 * 国产 ROM 的电池优化/清理可能移除精确闹钟;App 不被打开时靠本任务把闹钟补回来
 * (rescheduleAll 幂等,重复设置同一 PendingIntent 会覆盖,无副作用)。
 */
class ReminderSelfHealWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        runCatching {
            EntryPointAccessors.fromApplication(applicationContext, SelfHealEntryPoint::class.java)
                .notificationScheduler()
                .rescheduleAll()
        }
        return Result.success()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SelfHealEntryPoint {
        fun notificationScheduler(): NotificationScheduler
    }
}

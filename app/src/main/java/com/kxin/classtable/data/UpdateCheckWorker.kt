package com.kxin.classtable.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kxin.classtable.notify.Notifier
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 后台自动检查更新:每天一次(WorkManager 周期任务,仅在有网时跑)。
 *
 * 受设置里的「自动检查更新」开关与 24h 节流约束;发现新版本且未被用户忽略时,发一条
 * 可点进「检查更新」页的通知。**只提示,不后台下载**——安装始终由用户主动触发。
 * 依赖注入沿用项目既有做法(EntryPointAccessors),不引入 HiltWorkerFactory。
 */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(
            applicationContext,
            UpdateWorkerEntryPoint::class.java,
        ).updateRepository()

        if (!repository.shouldAutoCheck()) return Result.success()
        repository.markChecked()

        val info = repository.check().getOrElse { return Result.retry() }
        if (info.isNewer && info.latestVersion != repository.dismissedVersion()) {
            Notifier.showUpdateAvailable(applicationContext, info.latestVersion, info.notes)
        }
        return Result.success()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface UpdateWorkerEntryPoint {
        fun updateRepository(): UpdateRepository
    }
}

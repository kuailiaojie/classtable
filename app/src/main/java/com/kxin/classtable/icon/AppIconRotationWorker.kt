package com.kxin.classtable.icon

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.domain.model.IconCadence
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 后台轮换桌面图标(WorkManager 周期任务;「每次打开」节奏不走这里,见 [IconRotationScheduler])。
 *
 * 依赖注入沿用项目既有做法(EntryPointAccessors),不引入 HiltWorkerFactory。
 */
class AppIconRotationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(
            applicationContext,
            AppIconRotationEntryPoint::class.java,
        ).settingsRepository()

        val settings = repository.currentSettings()
        if (!settings.iconCarouselEnabled) return Result.success()
        if (IconCadence.of(settings.iconCarouselCadence) == IconCadence.LAUNCH) return Result.success()

        val next = AppIcon.nextIndex(settings.appIconIndex)
        repository.setAppIconIndex(next)
        // 进程可能只为跑这个任务被拉起来、跑完就被回收,设置流的收集者未必来得及切 alias,
        // 所以这里直接切一次(重复设置同一 alias 会被 applyAppIcon 跳过,幂等)。
        applyAppIcon(applicationContext, AppIcon.of(next))
        return Result.success()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppIconRotationEntryPoint {
        fun settingsRepository(): SettingsRepository
    }
}

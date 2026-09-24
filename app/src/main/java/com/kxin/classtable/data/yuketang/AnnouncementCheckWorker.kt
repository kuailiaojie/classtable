package com.kxin.classtable.data.yuketang

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.notify.Notifier
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 后台定时拉取雨课堂课程公告(WorkManager 周期任务,仅在有网时跑)。
 *
 * 与课程提醒**相互独立**:提醒走本地精确闹钟,这里只负责把公告缓存刷新到本机
 * (课程详情页与课前提醒都读这份缓存),有新公告时另发一条独立渠道的通知。
 *
 * 静默优先:未开启 / 未登录 / 登录失效都直接成功返回,不重试也不再打扰;只有真正的
 * 网络故障才 retry。
 * 依赖注入沿用项目既有做法(EntryPointAccessors),不引入 HiltWorkerFactory。
 */
class AnnouncementCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            AnnouncementWorkerEntryPoint::class.java,
        )
        val settingsRepository = entry.settingsRepository()
        val repository = entry.yuketangRepository()

        val settings = settingsRepository.currentSettings()
        if (!settings.yuketangEnabled || !settings.yuketangBackgroundFetch) return Result.success()
        if (!repository.isLoggedIn()) return Result.success()

        val result = try {
            repository.syncAnnouncements()
        } catch (e: NotLoggedInException) {
            return Result.success()
        } catch (e: Exception) {
            return Result.retry()
        }

        settingsRepository.markYuketangFetched()
        if (settings.yuketangNotifyNew && result.newCount > 0 && !result.latestTitle.isNullOrBlank()) {
            Notifier.showNewAnnouncement(
                context = applicationContext,
                courseName = result.latestCourseName.orEmpty(),
                title = result.latestTitle,
                count = result.newCount,
            )
        }
        return Result.success()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AnnouncementWorkerEntryPoint {
        fun settingsRepository(): SettingsRepository
        fun yuketangRepository(): YuketangRepository
    }
}

package com.kxin.classtable.data.yuketang

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.notify.PushNotifier
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

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
            // 未登录 / 登录失效:静默,不重试也不打扰
            return Result.success()
        } catch (e: YuketangException) {
            // 接口路径找不到、服务端报错这类业务失败:重试也不会自己好,记录后放弃本次。
            Log.w(TAG, "公告拉取失败:${e.message}")
            return Result.success()
        } catch (e: Exception) {
            // 网络抖动之类:交给 WorkManager 退避重试
            return Result.retry()
        }

        settingsRepository.markYuketangFetched()
        if (settings.yuketangNotifyNew && result.newCount > 0) {
            val titleText = result.latestTitle?.ifBlank { "点击查看最新公告" } ?: "点击查看最新公告"
            PushNotifier.showNewAnnouncement(
                context = applicationContext,
                courseName = result.latestCourseName.orEmpty(),
                title = titleText,
                count = result.newCount,
            )
        }
        return Result.success()
    }

    companion object {
        const val TAG = "YuketangSync"
        const val UNIQUE_WORK_NAME = "announcement_check"
        const val ONE_TIME_WORK_NAME = "announcement_check_onetime"

        /**
         * 注册/更新后台周期检查任务（每小时 1 次，有网时执行）。
         * 使用 [ExistingPeriodicWorkPolicy.UPDATE] 确保配置更新时即时生效。
         */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<AnnouncementCheckWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /**
         * 触发一次即时的后台公告检查（在登录、绑定课程或启动时调用）。
         */
        fun triggerOneTime(context: Context) {
            val request = OneTimeWorkRequestBuilder<AnnouncementCheckWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AnnouncementWorkerEntryPoint {
        fun settingsRepository(): SettingsRepository
        fun yuketangRepository(): YuketangRepository
    }
}

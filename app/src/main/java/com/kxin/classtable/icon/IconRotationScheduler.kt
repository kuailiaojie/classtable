package com.kxin.classtable.icon

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kxin.classtable.domain.model.IconCadence
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 图标轮播的排程。
 *
 * 后台节奏(每小时 / 每天)走 WorkManager;**「每次打开」不走后台** —— 它由
 * [claimLaunchRotation] 在进入界面时触发,不需要闹钟也不需要联网。
 *
 * [sync] 只在用户改动轮播设置时调用,不在启动时调用:每次启动都重排会把周期任务
 * 的计时重新拨到「现在 + 间隔」,天天开应用的人就永远等不到下一次轮换。
 */
object IconRotationScheduler {

    private const val WORK_NAME = "app_icon_rotation"

    /** 「每次打开」在进程内只轮换一次(旋转屏幕、从最近任务回来都会重建 Activity)。 */
    private val rotatedThisProcess = AtomicBoolean(false)

    /** 按当前设置重排后台轮换:关闭轮播或节奏为「每次打开」时不需要后台任务。 */
    fun sync(context: Context, enabled: Boolean, cadence: IconCadence) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(WORK_NAME)
        if (!enabled || cadence == IconCadence.LAUNCH) return

        val interval = if (cadence == IconCadence.HOURLY) 1L to TimeUnit.HOURS else 1L to TimeUnit.DAYS
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            PeriodicWorkRequestBuilder<AppIconRotationWorker>(interval.first, interval.second).build(),
        )
    }

    /** 这次进程启动是否该轮换「每次打开」节奏的图标(是则返回 true,并把名额用掉)。 */
    fun claimLaunchRotation(enabled: Boolean, cadence: IconCadence): Boolean {
        if (!enabled || cadence != IconCadence.LAUNCH) return false
        return rotatedThisProcess.compareAndSet(false, true)
    }
}

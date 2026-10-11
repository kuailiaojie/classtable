package com.kxin.classtable.notify

import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.local.AppDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 提醒链路上接收器 / 服务共用的依赖入口。
 *
 * 以前每个接收器都手工 `ReminderPlanner(ctx, AppDatabase.get(ctx).courseDao(), ..., SettingsRepository(ctx))`
 * —— 五处重复,且绕过了 Hilt 单例(MainActivity 与 ClasstableApp 用注入的那份,A 闹钟用另一份)。
 * 统一从这里取,与 [com.kxin.classtable.data.FcmMessagingService.FcmEntryPoint] 同一范式。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotifyEntryPoint {
    fun reminderPlanner(): ReminderPlanner
    fun settingsRepository(): SettingsRepository
    fun database(): AppDatabase
}

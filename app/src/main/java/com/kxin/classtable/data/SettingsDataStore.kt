package com.kxin.classtable.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * 共享的 DataStore 实例(单文件单实例约束,供设置与小组件共用)。
 *
 * 带 corruptionHandler:一次坏写会让底层文件无法解析,不处理的话此后**每次读都抛
 * CorruptionException**,表现为启动即崩、小组件永久报错。这里退化为「空设置」重来。
 */
internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

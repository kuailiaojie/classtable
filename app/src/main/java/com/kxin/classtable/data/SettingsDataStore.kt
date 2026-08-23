package com.kxin.classtable.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** 共享的 DataStore 实例(单文件单实例约束,供设置与小组件共用)。 */
internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

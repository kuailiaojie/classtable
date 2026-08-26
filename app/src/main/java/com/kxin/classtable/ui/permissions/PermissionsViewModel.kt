package com.kxin.classtable.ui.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kxin.classtable.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 提醒可靠性页状态:自启动为 ROM 引导项,荣耀等无公开 API 可查真实状态,
 * 以「进过应用启动管理页」作为已配置标记(持久化,仅本机)。
 */
@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** 是否已进过 ROM「应用启动管理」页。 */
    val autoStartVisited: StateFlow<Boolean> = settingsRepository.autoStartVisited
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 记录已进过应用启动管理页(点击自启动行跳转前调用)。 */
    fun markAutoStartVisited() {
        viewModelScope.launch { settingsRepository.markAutoStartVisited() }
    }
}

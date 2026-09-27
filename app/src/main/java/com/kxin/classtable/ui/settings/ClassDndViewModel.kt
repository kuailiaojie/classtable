package com.kxin.classtable.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.notify.DndController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 上课免打扰。UI 已并进「设置 → 提醒」分组页(见 [ClassDndSection] 所在文件)。 */
@HiltViewModel
class ClassDndViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun save(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setClassDndEnabled(enabled)
        // 关掉时立刻退出:否则这节课剩下的时间会一直停在免打扰里,直到下课闹钟(已被取消)之外没人管
        if (!enabled) DndController.exit(context)
    }
}

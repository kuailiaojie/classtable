package com.kxin.classtable.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.IconCadence
import com.kxin.classtable.icon.AppIcon
import com.kxin.classtable.icon.IconRotationScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 应用图标设置。UI 已并进「设置 → 外观」分组页(见 [AppearanceHub] 所在文件)。 */
@HiltViewModel
class AppIconViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    /** 手动选一张。真正的 alias 切换由 ClasstableApp 里那条设置流统一做。 */
    fun select(icon: AppIcon) = viewModelScope.launch {
        settingsRepository.setAppIconIndex(icon.ordinal)
    }

    /** 轮播开关与节奏:存下来,并按新节奏重排后台任务。 */
    fun setCarousel(enabled: Boolean, cadence: IconCadence) = viewModelScope.launch {
        settingsRepository.setIconCarousel(enabled, cadence)
        IconRotationScheduler.sync(context, enabled, cadence)
    }
}

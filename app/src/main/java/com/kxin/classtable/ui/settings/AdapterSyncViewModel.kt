package com.kxin.classtable.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kxin.classtable.data.importer.AdapterSyncInfo
import com.kxin.classtable.data.importer.AdapterSyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 适配器同步。UI 已并进「设置 → 导入与识别」分组页(见 [AdapterSyncSection] 所在文件)。 */
@HiltViewModel
class AdapterSyncViewModel @Inject constructor(
    private val repository: AdapterSyncRepository,
) : ViewModel() {
    private val _info = MutableStateFlow<AdapterSyncInfo?>(null)
    val info: StateFlow<AdapterSyncInfo?> = _info.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch { _info.value = repository.info() }
    }

    fun sync() = viewModelScope.launch {
        _busy.value = true
        _message.value = null
        repository.sync()
            .onSuccess {
                _info.value = it
                _message.value = "同步完成:${it.schoolCount} 所学校 / ${it.adapterCount} 个适配器"
            }
            .onFailure { _message.value = "同步失败:${it.message ?: "未知错误"}" }
        _busy.value = false
    }

    fun clear() = viewModelScope.launch {
        _busy.value = true
        _message.value = null
        repository.clear()
            .onSuccess {
                _info.value = it
                _message.value = "已恢复内置适配器"
            }
            .onFailure { _message.value = "操作失败:${it.message ?: "未知错误"}" }
        _busy.value = false
    }

    fun consumeMessage() {
        _message.value = null
    }
}

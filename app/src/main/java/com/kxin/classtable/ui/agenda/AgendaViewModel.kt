package com.kxin.classtable.ui.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kxin.classtable.data.AgendaRepository
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.Crash
import com.kxin.classtable.domain.model.AgendaEvent
import com.kxin.classtable.domain.model.Course
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AgendaViewModel @Inject constructor(
    private val repository: AgendaRepository,
    courseRepository: CourseRepository,
) : ViewModel() {
    val events: StateFlow<List<AgendaEvent>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val courses: StateFlow<List<Course>> = courseRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun save(event: AgendaEvent) {
        viewModelScope.launch {
            runCatching { repository.save(event) }
                .onSuccess { _saved.value = true }
                .onFailure {
                    Crash.recordException(it, "op" to "agenda_save")
                    _error.value = "保存失败,请重试"
                }
        }
    }

    fun toggleCompleted(id: String, completed: Boolean) {
        viewModelScope.launch {
            runCatching { repository.toggleCompleted(id, completed) }
                .onFailure {
                    Crash.recordException(it, "op" to "agenda_toggle_completed")
                    _error.value = "更新状态失败"
                }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { repository.delete(id) }
                .onSuccess { _saved.value = true }
                .onFailure {
                    Crash.recordException(it, "op" to "agenda_delete")
                    _error.value = "删除失败,请重试"
                }
        }
    }

    fun clearError() {
        _error.value = null
    }
}

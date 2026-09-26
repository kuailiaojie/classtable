package com.kxin.classtable.ui.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kxin.classtable.data.AgendaRepository
import com.kxin.classtable.domain.model.AgendaEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AgendaViewModel @Inject constructor(
    private val repository: AgendaRepository,
) : ViewModel() {
    val events: StateFlow<List<AgendaEvent>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(event: AgendaEvent) {
        viewModelScope.launch { runCatching { repository.save(event) } }
    }

    fun delete(id: String) {
        viewModelScope.launch { runCatching { repository.delete(id) } }
    }
}

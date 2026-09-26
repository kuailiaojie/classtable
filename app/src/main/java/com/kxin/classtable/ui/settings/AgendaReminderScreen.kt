package com.kxin.classtable.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTextField
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AgendaReminderViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    fun save(enabled: Boolean, allDayTime: String) = viewModelScope.launch {
        settingsRepository.setAgendaReminder(enabled, allDayTime)
        _saved.value = true
    }
}

/**
 * 日程提醒页(从设置页「提醒 → 日程提醒」进入)。
 *
 * 这里只放**全局**的部分:总开关,以及全天日程的提醒时刻。具体某条日程要不要提醒、
 * 提前多久,在它自己的编辑页里设(每条日程单独设)。
 */
@Composable
fun AgendaReminderScreen(
    nav: NavHostController,
    viewModel: AgendaReminderViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    var enabled by rememberSaveable { mutableStateOf(settings.agendaReminderEnabled) }
    var allDayTime by rememberSaveable { mutableStateOf(settings.agendaAllDayRemindTime) }
    var hydrated by remember { mutableStateOf(false) }

    // settings 流先发占位再发真实值;等真实值到位后只灌一次,避免编辑中被覆盖
    LaunchedEffect(settings) {
        if (!hydrated && settings != AppSettings()) {
            enabled = settings.agendaReminderEnabled
            allDayTime = settings.agendaAllDayRemindTime
            hydrated = true
        }
    }
    LaunchedEffect(saved) { if (saved) nav.popBackStack() }

    // 时刻填写不合法时不允许保存:否则到点不会触发,却没有任何提示
    val timeValid = Schedule.parseClock(allDayTime) != null
    val canSave = !enabled || timeValid

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(title = "日程提醒", onBack = { nav.popBackStack() })

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(title = "日程提醒") {
                SettingBlock(
                    title = "日程提醒",
                    subtitle = "开启后,在日程开始前提醒你。每条日程可在它自己的编辑页单独开关、并选提前多久。",
                ) {
                    ChipToggle(selected = enabled) { enabled = it }
                }
                if (enabled) {
                    DividerLine()
                    SettingBlock(
                        title = "全天日程提醒时刻",
                        subtitle = "「全天」日程没有具体时间,统一在这一天的这个时刻提醒。",
                    ) {
                        YohakuTextField(
                            value = allDayTime,
                            onValueChange = { allDayTime = it },
                            label = "时刻",
                            placeholder = "09:00",
                            isError = !timeValid,
                        )
                        if (!timeValid) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "请按 24 小时制填写,如 09:00",
                                style = YohakuType.label12,
                                color = colors.error,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(YohakuDimens.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            YohakuButton(
                text = "保存",
                onClick = { viewModel.save(enabled, allDayTime) },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

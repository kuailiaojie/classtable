package com.kxin.classtable.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class SemesterViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    fun save(startDay: Long, weekCount: Int) = viewModelScope.launch {
        settingsRepository.setSemester(startDay, weekCount)
        _saved.value = true
    }
}

/** 学期周次子页:开学日期 + 学期周数,自动推导当前周。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemesterScreen(
    nav: NavHostController,
    viewModel: SemesterViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    var startDay by rememberSaveable { mutableLongStateOf(settings.semesterStartDay) }
    var weekCount by rememberSaveable { mutableStateOf(settings.semesterWeekCount.toString()) }
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(saved) { if (saved) nav.popBackStack() }

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = if (startDay > 0L) startDay * 86_400_000L else System.currentTimeMillis(),
    )
    if (showPicker) {
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { startDay = it / 86_400_000L }
                        showPicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    val weekCountValue = weekCount.toIntOrNull() ?: 0
    val startLabel = if (startDay > 0L) {
        val d = LocalDate.ofEpochDay(startDay)
        "${d.year}年${d.monthValue}月${d.dayOfMonth}日"
    } else {
        "未设置"
    }
    val hint = if (startDay > 0L) {
        "按此设置,当前为第 ${Schedule.currentWeek(startDay, weekCountValue.coerceAtLeast(1))} 周"
    } else {
        "设置开学日期后,周视图的「今天」与单双周将自动校准"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(title = "学期周次", onBack = { nav.popBackStack() })

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = YohakuDimens.screenPadding),
        ) {
            Text(text = "开学日期", style = YohakuType.label12, color = colors.neutral7)
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = startLabel,
                    style = YohakuType.copy15,
                    color = colors.neutral9,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "选择日期",
                    style = YohakuType.copy13,
                    color = colors.accent,
                    modifier = Modifier
                        .clickable { showPicker = true }
                        .padding(8.dp),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.neutral5),
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            YohakuTextField(
                value = weekCount,
                onValueChange = { weekCount = it },
                label = "学期周数",
                placeholder = "如 20",
                isError = weekCountValue <= 0,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            Text(text = hint, style = YohakuType.copy13, color = colors.neutral7)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(YohakuDimens.screenPadding),
        ) {
            YohakuButton(
                text = "保存",
                onClick = { viewModel.save(startDay, weekCountValue.coerceAtLeast(1)) },
                enabled = weekCountValue > 0,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

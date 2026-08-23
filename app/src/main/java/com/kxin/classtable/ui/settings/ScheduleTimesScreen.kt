package com.kxin.classtable.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import javax.inject.Inject

@HiltViewModel
class ScheduleTimesViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    fun save(periods: List<Schedule.Period>) = viewModelScope.launch {
        settingsRepository.setPeriodTimes(Schedule.serializePeriods(periods))
        _saved.value = true
    }
}

/**
 * 作息时间子页:每节独立填「开始时间 + 结束时间」(时间段),
 * 可自由增删节,不限于 12 节;教务/AI/表格导入识别到作息时也会一键同步到这里。
 */
@Composable
fun ScheduleTimesScreen(
    nav: NavHostController,
    viewModel: ScheduleTimesViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    var pairs by remember {
        mutableStateOf(
            Schedule.parsePeriods(settings.periodTimes)
                .map { fmtTime(it.start) to fmtTime(it.end) },
        )
    }
    LaunchedEffect(settings.periodTimes) {
        pairs = Schedule.parsePeriods(settings.periodTimes)
            .map { fmtTime(it.start) to fmtTime(it.end) }
    }
    LaunchedEffect(saved) { if (saved) nav.popBackStack() }

    val parsed = pairs.map { (s, e) -> parseTime(s) to parseTime(e) }
    val allValid = parsed.all { (s, e) -> s != null && e != null && e > s }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(title = "作息时间", onBack = { nav.popBackStack() })

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = YohakuDimens.screenPadding),
        ) {
            Text(
                text = "每节课是一段时间:分别填开始与结束时间。可自由增删节,不限于 12 节。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Text(
                text = "从教务系统 / AI 图片 / 表格导入课表时,若识别到作息会一键同步到这里。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
            pairs.indices.forEach { i ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "第 ${i + 1} 节",
                        style = YohakuType.copy15,
                        color = colors.neutral9,
                        modifier = Modifier.width(56.dp),
                    )
                    YohakuTextField(
                        value = pairs[i].first,
                        onValueChange = { v ->
                            pairs = pairs.toMutableList().also { it[i] = v to it[i].second }
                        },
                        placeholder = "开始",
                        isError = parsed[i].first == null,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "–",
                        style = YohakuType.copy15,
                        color = colors.neutral6,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    YohakuTextField(
                        value = pairs[i].second,
                        onValueChange = { v ->
                            pairs = pairs.toMutableList().also { it[i] = it[i].first to v }
                        },
                        placeholder = "结束",
                        isError = run {
                            val (si, ei) = parsed[i]
                            si == null || ei == null || ei <= si
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (pairs.size > 1) {
                        Text(
                            text = "删",
                            style = YohakuType.label12,
                            color = colors.error,
                            modifier = Modifier
                                .clickable { pairs = pairs.toMutableList().also { it.removeAt(i) } }
                                .padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
            Text(
                text = "＋ 添加一节",
                style = YohakuType.copy13,
                color = colors.accent,
                modifier = Modifier
                    .clickable {
                        val lastEnd = parsed.lastOrNull()?.second ?: 480
                        pairs = pairs + (fmtTime(lastEnd) to fmtTime(lastEnd + Schedule.PERIOD_LENGTH_MIN))
                    }
                    .padding(vertical = 8.dp),
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(YohakuDimens.screenPadding),
        ) {
            YohakuButton(
                text = "保存",
                onClick = {
                    viewModel.save(
                        parsed.map { (s, e) -> Schedule.Period(s ?: 0, e ?: 0) },
                    )
                },
                enabled = allValid,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun fmtTime(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun parseTime(s: String): Int? {
    val parts = s.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

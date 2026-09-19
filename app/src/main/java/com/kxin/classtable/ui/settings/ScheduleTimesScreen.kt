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
 * 作息时间子页:每节独立填「开始时间 + 结束时间」(时间段),可自由增删节,不限于 12 节。
 * 「课程属于第几节」与「这一节具体几点」分离,所以改作息不会移动课程。
 * 提供自动生成(上午开始 + 单节时长 + 课间 + 节数),并保存前校验顺序与重叠。
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

    // 自动生成(自动匹配模式):上午开始 + 单节时长 + 课间 + 节数
    var showGenerator by rememberSaveable { mutableStateOf(false) }
    var genStart by rememberSaveable { mutableStateOf("08:00") }
    var genLength by rememberSaveable { mutableStateOf("45") }
    var genBreak by rememberSaveable { mutableStateOf("10") }
    var genCount by rememberSaveable { mutableStateOf("12") }

    val parsed = pairs.map { (s, e) -> parseTime(s) to parseTime(e) }
    val error = validatePeriods(parsed)

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
                text = "改这里只调整每节几点上,不会移动课程本身所属的节次。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))

            if (showGenerator) {
                Text(text = "自动生成时间线", style = YohakuType.label12, color = colors.neutral7)
                Spacer(modifier = Modifier.height(6.dp))
                Row {
                    YohakuTextField(
                        value = genStart,
                        onValueChange = { genStart = it },
                        label = "上午开始",
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    YohakuTextField(
                        value = genCount,
                        onValueChange = { genCount = it },
                        label = "节数",
                        isError = genCount.toIntOrNull()?.let { it in 1..30 } != true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    YohakuTextField(
                        value = genLength,
                        onValueChange = { genLength = it },
                        label = "单节时长(分)",
                        isError = (genLength.toIntOrNull() ?: 0) <= 0,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    YohakuTextField(
                        value = genBreak,
                        onValueChange = { genBreak = it },
                        label = "课间(分)",
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                val generated = buildTimeline(
                    start = parseTime(genStart),
                    length = genLength.toIntOrNull() ?: 0,
                    gap = genBreak.toIntOrNull() ?: 0,
                    count = genCount.toIntOrNull() ?: 0,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (generated != null) {
                            "将生成 ${generated.size} 节:" +
                                generated.first().let { "${it.first}–${it.second}" } +
                                " … " + generated.last().let { "${it.first}–${it.second}" }
                        } else {
                            "填好后生成,会覆盖下方当前时间线"
                        },
                        style = YohakuType.label12,
                        color = if (generated != null) colors.neutral7 else colors.error,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "生成",
                        style = YohakuType.copy13,
                        color = if (generated != null) colors.accent else colors.neutral5,
                        modifier = Modifier
                            .clickable(enabled = generated != null) {
                                pairs = generated!!
                                showGenerator = false
                            }
                            .padding(8.dp),
                    )
                    Text(
                        text = "收起",
                        style = YohakuType.copy13,
                        color = colors.neutral7,
                        modifier = Modifier
                            .clickable { showGenerator = false }
                            .padding(8.dp),
                    )
                }
                Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
            } else {
                Text(
                    text = "＋ 自动生成时间线",
                    style = YohakuType.copy13,
                    color = colors.accent,
                    modifier = Modifier
                        .clickable { showGenerator = true }
                        .padding(vertical = 8.dp),
                )
                Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
            }

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
            error?.let {
                Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
                Text(text = it, style = YohakuType.label12, color = colors.error)
            }
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
                enabled = error == null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 保存前校验:格式 / 起止先后 / 节次顺序与重叠(与参考实现一致,避免存下冲突时间表)。 */
private fun validatePeriods(parsed: List<Pair<Int?, Int?>>): String? {
    if (parsed.isEmpty()) return "至少需要一节"
    parsed.forEachIndexed { i, (s, e) ->
        if (s == null || e == null) return "第 ${i + 1} 节时间格式应为 HH:MM"
        if (e <= s) return "第 ${i + 1} 节结束时间需晚于开始时间"
    }
    for (i in 1 until parsed.size) {
        val (ps, pe) = parsed[i - 1]
        val (cs, ce) = parsed[i]
        if (cs!! < ps!!) return "第 ${i + 1} 节早于第 $i 节,请按时间先后排列"
        if (cs < pe!!) return "第 ${i + 1} 节与第 $i 节时间重叠"
    }
    return null
}

/** 按「上午开始 + 单节时长 + 课间 + 节数」生成时间线;参数非法返回 null。 */
private fun buildTimeline(start: Int?, length: Int, gap: Int, count: Int): List<Pair<String, String>>? {
    if (start == null || length <= 0 || count !in 1..30) return null
    val out = ArrayList<Pair<String, String>>(count)
    var cursor = start
    repeat(count) {
        out.add(fmtTime(cursor) to fmtTime(cursor + length))
        cursor += length + gap.coerceAtLeast(0)
    }
    return out
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

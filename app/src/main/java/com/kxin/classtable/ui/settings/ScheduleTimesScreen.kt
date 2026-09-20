package com.kxin.classtable.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
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
import com.kxin.classtable.design.YohakuOutlineButton
import com.kxin.classtable.design.YohakuTextField
import com.kxin.classtable.design.YohakuTimePicker
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
import kotlin.math.roundToInt

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
 * 作息时间子页:把一天画成一条**时间线**。
 *
 * - 方块交替排布:「第 N 节」是一块浮起的卡片,两块之间是「课间」——课间不是独立数据,
 *   而是相邻两节之间的空隙,所以改一节的时长,课间和后面所有节自动跟着走,不会出现
 *   「时间重叠」这种需要靠保存前校验兜住的状态;
 * - 方块高度按分钟成比例([MINUTE_DP]),所以整条时间线一眼能看出哪节长、哪个课间久;
 * - **点按**方块选时间(改这一节的开始时刻),**拖右下角**调时长,各 1 分钟一档;
 * - 按「上午 / 下午 / 晚上」分段,段首标出起始时刻。
 *
 * 「课程属于第几节」与「这一节具体几点」分离,因此改作息不会移动课程。
 */
@Composable
fun ScheduleTimesScreen(
    nav: NavHostController,
    viewModel: ScheduleTimesViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    var timeline by remember { mutableStateOf(periodsToTimeline(Schedule.parsePeriods(settings.periodTimes))) }
    LaunchedEffect(settings.periodTimes) {
        timeline = periodsToTimeline(Schedule.parsePeriods(settings.periodTimes))
    }
    LaunchedEffect(saved) { if (saved) nav.popBackStack() }

    // 正在改的那一刻时间(点时间片打开选择器)
    var timeEdit by remember { mutableStateOf<TimeEdit?>(null) }

    // 自动生成(自动匹配模式):上午开始 + 单节时长 + 课间 + 节数
    var showGenerator by rememberSaveable { mutableStateOf(false) }
    var genStart by rememberSaveable { mutableStateOf("08:00") }
    var genLength by rememberSaveable { mutableStateOf("45") }
    var genBreak by rememberSaveable { mutableStateOf("10") }
    var genCount by rememberSaveable { mutableStateOf("12") }
    var editingGenStart by remember { mutableStateOf(false) }

    timeEdit?.let { edit ->
        YohakuTimePicker(
            title = edit.title,
            initial = Schedule.clockText(edit.initial),
            onConfirm = { value ->
                Schedule.parseClock(value)?.let { minute -> timeline = edit.apply(timeline, minute) }
                timeEdit = null
            },
            onDismiss = { timeEdit = null },
        )
    }
    if (editingGenStart) {
        YohakuTimePicker(
            title = "上午开始时间",
            initial = genStart,
            onConfirm = { genStart = it; editingGenStart = false },
            onDismiss = { editingGenStart = false },
        )
    }

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
                text = "点任意一个时间,改的就是那一刻;拖方块右下角可以拉长 / 缩短这一节。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Text(
                text = "每一节都能单独删;「添加一节」往末尾再加。课间是相邻两节之间的空隙,自动得出。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Text(
                text = "课程的时刻在导入 / 新建时已按当时的作息固定,改这张表不会再移动它们(要改请编辑那门课)。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))

            if (showGenerator) {
                Text(text = "自动生成时间线", style = YohakuType.label12, color = colors.neutral7)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 与右侧「节数」输入框同构:标签 + 控件,两列才对得齐
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "上午开始", style = YohakuType.label12, color = colors.neutral7)
                        Spacer(modifier = Modifier.height(4.dp))
                        TimeChip(
                            value = genStart,
                            isError = false,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { editingGenStart = true },
                        )
                    }
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
                    start = Schedule.parseClock(genStart),
                    length = genLength.toIntOrNull() ?: 0,
                    gap = genBreak.toIntOrNull() ?: 0,
                    count = genCount.toIntOrNull() ?: 0,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (generated != null) {
                            "将生成 ${generated.size} 节:" +
                                Schedule.clockText(generated.first().start) +
                                " … " + Schedule.clockText(generated.last().end)
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
                                timeline = periodsToTimeline(generated!!)
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

            val spans = timeline.spans()
            val classCount = spans.count { !it.block.isBreak }
            var section: String? = null
            spans.forEach { span ->
                val currentSection = sectionOf(span.start)
                if (currentSection != section) {
                    section = currentSection
                    SectionHeader(name = currentSection, startMinute = span.start)
                }
                // 每块的两个时刻各自可点,含义和「拖」一致:改结束 = 改这块自己的时长,
                // 改开始 = 改它前面那块(首块改的是整条时间线的锚点),后面的块顺移。
                val editEnd: () -> Unit = {
                    timeEdit = TimeEdit("结束时间", span.end) { tl, minute ->
                        tl.withMinutes(span.index, minute - span.start)
                    }
                }
                val editStart: () -> Unit = start@{
                    if (!span.block.isBreak) {
                        timeEdit = TimeEdit("第 ${span.classNumber} 节 · 开始时间", span.start) { tl, minute ->
                            tl.withClassStart(span.classNumber, minute)
                        }
                        return@start
                    }
                    // 课间的开始 = 前一节的结束,改它等同于改前一节的下课时间
                    val prev = spans.getOrNull(span.index - 1) ?: return@start
                    timeEdit = TimeEdit("课间开始时间", prev.end) { tl, minute ->
                        tl.withMinutes(prev.index, minute - prev.start)
                    }
                }
                if (span.block.isBreak) {
                    BreakBlock(
                        span = span,
                        onResize = { timeline = timeline.withMinutes(span.index, it) },
                        onEditStart = editStart,
                        onEditEnd = editEnd,
                    )
                } else {
                    ClassBlock(
                        span = span,
                        canDelete = classCount > 1,
                        onResize = { timeline = timeline.withMinutes(span.index, it) },
                        onEditStart = editStart,
                        onEditEnd = editEnd,
                        onDelete = { timeline = timeline.withoutClass(span.classNumber) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            YohakuOutlineButton(
                text = "＋ 添加一节",
                onClick = { timeline = timeline.plusClass() },
                modifier = Modifier.fillMaxWidth(),
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
                onClick = { viewModel.save(timeline.toPeriods()) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------------------------------------------------------------- 时间线模型

/** 时间线上的一块。时长决定它在时间线上的位置(整条线锚在第一节课的开始时刻),
 *  [number] 是这一节的**节次号**(跟着数据走,与它在时间线上的先后无关)。 */
private data class TimeBlock(val isBreak: Boolean, val minutes: Int, val number: Int = 0)

private data class Timeline(val anchor: Int, val blocks: List<TimeBlock>) {
    /** 每块的绝对起止(时长为前缀和)。[Span.classNumber] 是「第几节」,课间取它前面那节的序号。 */
    fun spans(): List<Span> {
        var cursor = anchor
        var classNo = 0
        return blocks.mapIndexed { index, block ->
            if (!block.isBreak) classNo++
            Span(index, block, cursor, cursor + block.minutes, classNo).also { cursor += block.minutes }
        }
    }

    /** 改某块的时长;夹到「至少 1 分钟」且不越过当天末尾。 */
    fun withMinutes(index: Int, minutes: Int): Timeline {
        val start = spans().getOrNull(index)?.start ?: return this
        val limit = (DAY_END - start).coerceAtLeast(MIN_BLOCK_MINUTES)
        val capped = minutes.coerceIn(MIN_BLOCK_MINUTES, limit)
        if (blocks[index].minutes == capped) return this
        return copy(blocks = blocks.toMutableList().also { it[index] = it[index].copy(minutes = capped) })
    }

    /**
     * 把第 [classNumber] 节的开始时刻改到 [newStart]。
     * 首节动的是整条线的锚点(全天平移);其余节改的是**它前面那段课间**的时长,
     * 于是这一节自身的时长不变、后面的块整体后移 —— 与「拖动这一节往下」的直觉一致。
     */
    fun withClassStart(classNumber: Int, newStart: Int): Timeline {
        val spans = spans()
        val target = spans.firstOrNull { !it.block.isBreak && it.classNumber == classNumber } ?: return this
        if (target.index == 0) return copy(anchor = newStart.coerceIn(0, DAY_END - MIN_BLOCK_MINUTES))
        val gapBefore = spans.getOrNull(target.index - 1) ?: return this
        return withMinutes(gapBefore.index, newStart - gapBefore.start)
    }

    /** 删一节:连同它前面那段课间一起删(没有前一段时删后一段),保证「节 / 课间」仍然交替。 */
    fun withoutClass(classNumber: Int): Timeline {
        val target = spans().firstOrNull { !it.block.isBreak && it.classNumber == classNumber } ?: return this
        val drop = when {
            target.index > 0 && blocks[target.index - 1].isBreak -> setOf(target.index, target.index - 1)
            target.index + 1 < blocks.size && blocks[target.index + 1].isBreak -> setOf(target.index, target.index + 1)
            else -> setOf(target.index)
        }
        val kept = blocks.filterIndexed { i, _ -> i !in drop }
        return copy(blocks = kept.ifEmpty { listOf(TimeBlock(false, DEFAULT_CLASS_MINUTES)) })
    }

    /** 末尾接一节:先补一段课间,再放一节;当天排满(放不下)时保持原样。 */
    fun plusClass(): Timeline {
        val last = spans().lastOrNull() ?: return copy(anchor = DEFAULT_START, blocks = listOf(TimeBlock(false, DEFAULT_CLASS_MINUTES)))
        val room = DAY_END - last.end
        if (room < MIN_BLOCK_MINUTES * 2) return this
        val gap = DEFAULT_BREAK_MINUTES.coerceAtMost(room - MIN_BLOCK_MINUTES)
        val length = DEFAULT_CLASS_MINUTES.coerceAtMost(room - gap)
        return copy(blocks = blocks + listOf(TimeBlock(true, gap), TimeBlock(false, length)))
    }

    fun toPeriods(): List<Schedule.Period> =
        spans().filter { !it.block.isBreak }
            .map { Schedule.Period(it.start, it.end, it.block.number) }
}

private data class Span(
    val index: Int,
    val block: TimeBlock,
    val start: Int,
    val end: Int,
    val classNumber: Int,
)

/** 正在编辑的那一刻:[title] 是弹层标题,[apply] 把选中的分钟数作用到时间线上。 */
private data class TimeEdit(
    val title: String,
    val initial: Int,
    val apply: (Timeline, Int) -> Timeline,
)

private const val DAY_END = 24 * 60 - 1
private const val MIN_BLOCK_MINUTES = 1
private const val DEFAULT_START = 8 * 60
private const val DEFAULT_CLASS_MINUTES = 45
private const val DEFAULT_BREAK_MINUTES = 10

/** 方块高度 = 分钟数 × 每分高度(有下限,短课间才不会窄到看不清)。 */
private val MINUTE_DP = 2.2.dp
// 下限按「标题行 + 时间片行」的可读高度定,不只是为了不重叠
private val MIN_CLASS_DP = 84.dp
private val MIN_BREAK_DP = 44.dp

private fun periodsToTimeline(periods: List<Schedule.Period>): Timeline {
    if (periods.isEmpty()) return Timeline(DEFAULT_START, listOf(TimeBlock(false, DEFAULT_CLASS_MINUTES)))
    val blocks = ArrayList<TimeBlock>(periods.size * 2)
    periods.forEachIndexed { i, p ->
        blocks += TimeBlock(false, (p.end - p.start).coerceAtLeast(MIN_BLOCK_MINUTES), p.number)
        val next = periods.getOrNull(i + 1) ?: return@forEachIndexed
        val gap = next.start - p.end
        if (gap > 0) blocks += TimeBlock(true, gap)
    }
    return Timeline(periods.first().start, blocks)
}

private fun sectionOf(minute: Int): String = when {
    minute < 12 * 60 -> "上午"
    minute < 18 * 60 -> "下午"
    else -> "晚上"
}

private fun blockHeight(minutes: Int, minHeight: Dp): Dp = (MINUTE_DP * minutes).coerceAtLeast(minHeight)

// ---------------------------------------------------------------- 时间线 UI

@Composable
private fun SectionHeader(name: String, startMinute: Int) {
    val colors = LocalYohakuColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = name, style = YohakuType.copy15, color = colors.neutral9)
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.line),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = Schedule.clockText(startMinute), style = YohakuType.timeMono, color = colors.neutral7)
    }
}

/**
 * 一节课:浮起卡片 + 左侧 accent 条。
 *
 * 两个时刻各自是**可点的时间片**,点哪个就改哪个;右下角还能直接拖。不给整张卡片绑点击 ——
 * 之前「点卡片 = 改开始时间」是这一页最不直观的地方:看得见的目标(时间)才该是可点的目标。
 */
@Composable
private fun ClassBlock(
    span: Span,
    canDelete: Boolean,
    onResize: (Int) -> Unit,
    onEditStart: () -> Unit,
    onEditEnd: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    val shape = RoundedCornerShape(YohakuDimens.radiusCard)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(blockHeight(span.block.minutes, MIN_CLASS_DP))
            .clip(shape)
            .background(colors.raised)
            .border(1.dp, colors.line, shape),
    ) {
        // 左侧 accent 条:结构信号「这是一节课」,不承载状态
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(YohakuDimens.accentBarWidth)
                .fillMaxHeight()
                .background(colors.accent),
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .padding(
                    start = YohakuDimens.accentBarWidth + 12.dp,
                    end = 12.dp,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // 显示节次号(来自数据);新加的行还没号,先用它在时间线上的序位顶上
                    text = "第 ${span.block.number.takeIf { it >= 1 } ?: span.classNumber} 节",
                    style = YohakuType.courseName,
                    color = colors.neutral10,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (canDelete) "删除" else "",
                    style = YohakuType.label12,
                    color = colors.error,
                    modifier = Modifier
                        .clickable(enabled = canDelete, onClick = onDelete)
                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeChip(
                    value = Schedule.clockText(span.start),
                    isError = false,
                    onClick = onEditStart,
                )
                Text(
                    text = "–",
                    style = YohakuType.timeMono,
                    color = colors.neutral6,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                TimeChip(
                    value = Schedule.clockText(span.end),
                    isError = false,
                    onClick = onEditEnd,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${span.block.minutes} 分钟",
                    style = YohakuType.title20,
                    color = colors.neutral10,
                    // 给右下角手柄让出位置,大字不会被角标压住
                    modifier = Modifier.padding(end = 18.dp),
                )
            }
        }
        ResizeHandle(
            minutes = span.block.minutes,
            onSet = onResize,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

/** 课间:相邻两节之间的空隙,不做成卡片(它是「之间」,不是一件东西),只留一行淡字 + 两个时间片。 */
@Composable
private fun BreakBlock(
    span: Span,
    onResize: (Int) -> Unit,
    onEditStart: () -> Unit,
    onEditEnd: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(blockHeight(span.block.minutes, MIN_BREAK_DP)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = YohakuDimens.accentBarWidth + 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "课间", style = YohakuType.label12, color = colors.neutral6)
            Spacer(modifier = Modifier.width(10.dp))
            TimeChip(
                value = Schedule.clockText(span.start),
                isError = false,
                compact = true,
                onClick = onEditStart,
            )
            Text(
                text = "–",
                style = YohakuType.timeMono,
                color = colors.neutral5,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            TimeChip(
                value = Schedule.clockText(span.end),
                isError = false,
                compact = true,
                onClick = onEditEnd,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${span.block.minutes} 分钟",
                style = YohakuType.copy13,
                color = colors.neutral6,
                modifier = Modifier.padding(end = 16.dp),
            )
        }
        ResizeHandle(
            minutes = span.block.minutes,
            onSet = onResize,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

/**
 * 右下角拖拽手柄:向下拖 = 这块变长,1 分钟一档。
 *
 * 角标用 n-5 的细线而不是 accent:卡片左侧已经有一条 accent 条,再加一个 accent 手柄
 * 就变成「一处强调」变两处;它是可操作性的提示,不该抢视线。
 *
 * 位移→分钟的换算用与方块高度同一把尺子([MINUTE_DP]),所以拖的时候方块边缘基本跟着手指走。
 * 起点在按下时取快照(`base`),之后再按累计位移算目标值 —— 若改成「每次增量叠加到当前值」,
 * 位移量会被重复计入,越拖越飘。
 */
@Composable
private fun ResizeHandle(
    minutes: Int,
    onSet: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    val current by rememberUpdatedState(minutes)
    val set by rememberUpdatedState(onSet)
    val minutePx = with(LocalDensity.current) { MINUTE_DP.toPx() }
    var base by remember { mutableIntStateOf(minutes) }
    var traveled by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .size(48.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        base = current
                        traveled = 0f
                    },
                ) { change, amount ->
                    change.consume()
                    traveled += amount.y
                    set(base + (traveled / minutePx).roundToInt())
                }
            }
            // 吃掉点按:手柄上的一下不该被理解成「点方块选时间」
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.BottomEnd,
    ) {
        Canvas(
            modifier = Modifier
                .padding(12.dp)
                .size(12.dp),
        ) {
            val stroke = 1.5.dp.toPx()
            val w = size.width
            val h = size.height
            // 右下角的 L 形角标(两条细线,和整站的细线语言一致)
            drawLine(colors.neutral5, Offset(w, 0f), Offset(w, h), strokeWidth = stroke)
            drawLine(colors.neutral5, Offset(w, h), Offset(0f, h), strokeWidth = stroke)
        }
    }
}

/**
 * 可点的时间片。编辑页里每个时刻都是一个,点它改那一刻;生成器里复用它做「上午开始」。
 * [compact] 给课间那种矮行用(竖向内边距收紧)。
 */
@Composable
private fun TimeChip(
    value: String,
    isError: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    val shape = RoundedCornerShape(YohakuDimens.radiusControl)
    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.raised)
            .border(1.dp, if (isError) colors.error else colors.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 6.dp else 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = value.ifBlank { "选择" },
            style = YohakuType.timeMono,
            color = if (isError) colors.error else colors.neutral9,
        )
    }
}

/** 按「上午开始 + 单节时长 + 课间 + 节数」生成时间线;参数非法返回 null。 */
private fun buildTimeline(start: Int?, length: Int, gap: Int, count: Int): List<Schedule.Period>? {
    if (start == null || length <= 0 || count !in 1..30) return null
    val out = ArrayList<Schedule.Period>(count)
    var cursor = start
    repeat(count) {
        if (cursor + length > DAY_END) return null
        out.add(Schedule.Period(cursor, cursor + length))
        cursor += length + gap.coerceAtLeast(0)
    }
    return out
}

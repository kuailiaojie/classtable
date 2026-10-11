package com.kxin.classtable.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import com.kxin.classtable.design.yohakuTouchTarget
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
 * 作息时间子页:把一天列成一张**清单** —— 一行一节,课间夹在相邻两节之间。
 *
 * 用清单而不是按分钟成比例的方块,是因为「一天上了什么」本来就是人按顺序读的东西:
 * 一行一节「第 N 节 08:00–08:45 · 45 分钟」,时长用「－ / ＋」按 5 分钟一档调,
 * 点任意时刻弹出选择器改到精确值。课间不是独立数据,而是两节之间的空隙,所以改一节时长,
 * 后面的课间与其它节自动顺移 —— 不会出现「时间重叠」这种要靠校验兜住的状态。
 *
 * 「课程属于第几节」与「这一节具体几点」分离,因此改作息不会移动已建课程。
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
        YohakuTopBar(title = "上课时间", onBack = { nav.popBackStack() })

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = YohakuDimens.screenPadding),
        ) {
            Text(
                text = "点时间改那一刻,时长用「－ / ＋」;课间夹在两节之间,可改长短或插一节。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Text(
                text = "改这里不会移动已建课程的时刻。",
                style = YohakuType.label12,
                color = colors.neutral6,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))

            if (showGenerator) {
                Text(text = "自动生成", style = YohakuType.label12, color = colors.neutral7)
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
                            .yohakuTouchTarget()
                            .clickable { showGenerator = false }
                            .padding(8.dp),
                    )
                }
                Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
            } else {
                Text(
                    text = "＋ 自动生成",
                    style = YohakuType.copy13,
                    color = colors.accent,
                    modifier = Modifier
                        .yohakuTouchTarget()
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
                if (span.block.isBreak) {
                    BreakRow(
                        span = span,
                        onResize = { timeline = timeline.withMinutes(span.index, it) },
                        onEditStart = { timeEdit = breakStartEdit(spans, span) },
                        onEditEnd = { timeEdit = endEdit(span) },
                        onAddClass = { timeline = timeline.plusClassAfter(span.index) },
                    )
                } else {
                    ClassRow(
                        span = span,
                        canDelete = classCount > 1,
                        onResize = { timeline = timeline.withMinutes(span.index, it) },
                        onEditStart = { timeEdit = classStartEdit(span) },
                        onEditEnd = { timeEdit = endEdit(span) },
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

// ---------------------------------------------------------------- 编辑意图

/** 改这一节的开始时刻:首节动的是整条线的锚点,其余节改的是**它前面那段课间**。 */
private fun classStartEdit(span: Span): TimeEdit =
    TimeEdit("第 ${span.classNumber} 节 · 开始时间", span.start) { tl, minute ->
        tl.withClassStart(span.classNumber, minute)
    }

/** 改课间的开始 = 改前一节的下课时间。 */
private fun breakStartEdit(spans: List<Span>, span: Span): TimeEdit {
    val prev = spans.getOrNull(span.index - 1)
    return if (prev == null) TimeEdit("课间开始时间", span.start) { tl, _ -> tl }
    else TimeEdit("课间开始时间", prev.end) { tl, minute -> tl.withMinutes(prev.index, minute - prev.start) }
}

/** 改结束 = 改这一块自己的时长,后面的块顺移。 */
private fun endEdit(span: Span): TimeEdit =
    TimeEdit("结束时间", span.end) { tl, minute -> tl.withMinutes(span.index, minute - span.start) }

// ---------------------------------------------------------------- 时间线模型

/** 时间线上的一块。时长决定它在时间线上的位置:整条线锚在第一节的开始时刻,改一块的时长,后面的块整体后移。 */
private data class TimeBlock(val isBreak: Boolean, val minutes: Int)

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
        if (blocks.getOrNull(index)?.minutes == capped) return this
        return copy(blocks = blocks.toMutableList().also { it[index] = it[index].copy(minutes = capped) })
    }

    /**
     * 把第 [classNumber] 节的开始时刻改到 [newStart]。
     * 首节动的是整条线的锚点(全天平移);其余节改的是**它前面那段课间**的时长,
     * 于是这一节自身的时长不变、后面的块整体后移 —— 与「把这一节往下挪」的直觉一致。
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

    /**
     * 在指定的**课间**处插入一节:保留这段课间,并在它后面补一节 + 一段新的课间。
     * 以前「添加一节」只能往末尾追加,中间想插一节做不到 —— 这正是要补上的那件事。
     */
    fun plusClassAfter(breakIndex: Int): Timeline {
        val brk = spans().getOrNull(breakIndex) ?: return this
        if (!brk.block.isBreak) return this
        val room = DAY_END - brk.end
        if (room < MIN_BLOCK_MINUTES * 2) return this
        val gap = DEFAULT_BREAK_MINUTES.coerceAtMost(room - MIN_BLOCK_MINUTES)
        val length = DEFAULT_CLASS_MINUTES.coerceAtMost(room - gap)
        val next = blocks.toMutableList().apply {
            add(breakIndex + 1, TimeBlock(false, length))
            add(breakIndex + 2, TimeBlock(true, gap))
        }
        return copy(blocks = next)
    }

    fun toPeriods(): List<Schedule.Period> =
        spans().filter { !it.block.isBreak }.map { Schedule.Period(it.start, it.end) }
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

/** 「－ / ＋」一档几分钟。要精确到分钟就用时间选择器点时刻。 */
private const val STEP_MINUTES = 5

private fun periodsToTimeline(periods: List<Schedule.Period>): Timeline {
    if (periods.isEmpty()) return Timeline(DEFAULT_START, listOf(TimeBlock(false, DEFAULT_CLASS_MINUTES)))
    val blocks = ArrayList<TimeBlock>(periods.size * 2)
    periods.forEachIndexed { i, p ->
        blocks += TimeBlock(false, (p.end - p.start).coerceAtLeast(MIN_BLOCK_MINUTES))
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

// ---------------------------------------------------------------- 清单 UI

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
 * 一节课:一行卡片 —— 上行「第 N 节 / 删除」,下行「开始 – 结束 · ± 时长」。
 *
 * 两个时刻各自是**可点的时间片**,点哪个改哪个;时长用「－ / ＋」按 5 分钟一档调。
 * 不给整张卡片绑点击 —— 看得见的目标(时间、时长)才该是可点的目标。
 */
@Composable
private fun ClassRow(
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
                .fillMaxWidth()
                .padding(
                    start = YohakuDimens.accentBarWidth + 12.dp,
                    end = 12.dp,
                    top = 10.dp,
                    bottom = 10.dp,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "第 ${span.classNumber} 节",
                    style = YohakuType.courseName,
                    color = colors.neutral10,
                    modifier = Modifier.weight(1f),
                )
                if (canDelete) {
                    Text(
                        text = "删除",
                        style = YohakuType.label12,
                        color = colors.error,
                        modifier = Modifier
                            .yohakuTouchTarget()
                            .clickable(onClick = onDelete)
                            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                    )
                }
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
                StepButton(text = "－") { onResize(span.block.minutes - STEP_MINUTES) }
                Text(
                    text = "${span.block.minutes} 分钟",
                    style = YohakuType.copy13,
                    color = colors.neutral9,
                )
                StepButton(text = "＋") { onResize(span.block.minutes + STEP_MINUTES) }
            }
        }
    }
}

/**
 * 课间:相邻两节之间的空隙,不做成卡片(它是「之间」,不是一件东西)。
 * 「加一节」在这段课间处插进一节新课;长短用「－ / ＋」按 5 分钟一档调。
 */
@Composable
private fun BreakRow(
    span: Span,
    onResize: (Int) -> Unit,
    onEditStart: () -> Unit,
    onEditEnd: () -> Unit,
    onAddClass: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = YohakuDimens.accentBarWidth + 12.dp,
                end = 12.dp,
                top = 4.dp,
                bottom = 4.dp,
            ),
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
            text = "加一节",
            style = YohakuType.label12,
            color = colors.accent,
            modifier = Modifier
                .yohakuTouchTarget()
                .clickable(onClick = onAddClass)
                .padding(horizontal = 6.dp, vertical = 8.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        StepButton(text = "－") { onResize(span.block.minutes - STEP_MINUTES) }
        Text(
            text = "${span.block.minutes} 分钟",
            style = YohakuType.copy13,
            color = colors.neutral6,
        )
        StepButton(text = "＋") { onResize(span.block.minutes + STEP_MINUTES) }
    }
}

/** 「－ / ＋」步进钮:统一触达范围与字形,避免每处各写一遍内边距。 */
@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    val colors = LocalYohakuColors.current
    Text(
        text = text,
        style = YohakuType.copy15,
        color = colors.neutral6,
        modifier = Modifier
            .yohakuTouchTarget(minWidth = 40.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    )
}

/**
 * 可点的时间片。清单里每个时刻都是一个,点它改那一刻;生成器里复用它做「上午开始」。
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

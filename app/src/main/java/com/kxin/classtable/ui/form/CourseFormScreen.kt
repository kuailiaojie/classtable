package com.kxin.classtable.ui.form

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.CoursePalette
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTextField
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.WeekSpec
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.WeekType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class CourseFormViewModel @Inject constructor(
    private val courseRepository: CourseRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    var editing by mutableStateOf<Course?>(null)
        private set

    fun load(courseId: String?) {
        if (courseId == null) return
        viewModelScope.launch { editing = courseRepository.get(courseId) }
    }

    fun save(course: Course) {
        viewModelScope.launch {
            // 本地失败也不阻断返回:避免保存/删除后页面卡住
            runCatching { courseRepository.save(course) }
            _saved.value = true
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { courseRepository.delete(id) }
            _saved.value = true
        }
    }
}

/**
 * 添加/编辑课程:整页表单,下划线输入,chip 选中 = accent 描边。
 * 课程名输入框内用衬线(所见即所得)。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CourseFormScreen(
    nav: NavHostController,
    courseId: String?,
    viewModel: CourseFormViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val editing = viewModel.editing
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val periods = remember(settings.periodTimes) { Schedule.parsePeriods(settings.periodTimes) }
    val weekCount = settings.semesterWeekCount.coerceAtLeast(1)

    var name by rememberSaveable { mutableStateOf("") }
    var teacher by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var weekdaysMask by rememberSaveable { mutableIntStateOf(1) }   // 位掩码:bit(day-1)=1
    var periodStart by rememberSaveable { mutableIntStateOf(1) }   // 节次区间起
    var periodEnd by rememberSaveable { mutableIntStateOf(1) }     // 节次区间止
    var pickingEnd by rememberSaveable { mutableStateOf(false) }   // 是否正在选结束节
    var weekTypeIdx by rememberSaveable { mutableIntStateOf(0) }
    /** 单/双周的起止周。结束周留空 = 到学期末。 */
    var rangeStart by rememberSaveable { mutableStateOf("1") }
    var rangeEnd by rememberSaveable { mutableStateOf("") }
    /** 「自定义」勾选到的周次,CSV(如 "4,6,8")。 */
    var customWeeks by rememberSaveable { mutableStateOf("") }
    var timeMode by rememberSaveable { mutableIntStateOf(0) }      // 0=按节次 1=自定义时间
    var customTimeStart by rememberSaveable { mutableStateOf("18:30") }
    var customTimeEnd by rememberSaveable { mutableStateOf("20:00") }
    var colorHex by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.load(courseId) }
    LaunchedEffect(editing) {
        editing?.let { c ->
            name = c.name
            teacher = c.teacher
            location = c.location
            note = c.note
            colorHex = c.colorHex
            weekdaysMask = if (c.weekdays > 0) c.weekdays else 1 shl (c.weekday - 1)
            val spec = WeekSpec.of(c, weekCount)
            weekTypeIdx = WeekType.entries.indexOf(spec.type).coerceAtLeast(0)
            // 每周不吃范围,起止给整学期,免得切到单双周时被旧默认的 16 截断
            rangeStart = (if (spec.type == WeekType.EVERY_WEEK) 1 else spec.start).toString()
            rangeEnd = (if (spec.type == WeekType.EVERY_WEEK) weekCount else spec.end).toString()
            customWeeks = WeekSpec.encode(spec.weeks)
            if (c.isCustomScheduled()) {
                timeMode = 1
                customTimeStart = Schedule.clockText(c.customStartMinute ?: 0)
                customTimeEnd = Schedule.clockText(c.customEndMinute ?: 0)
            } else {
                timeMode = 0
                val max = periods.size.coerceAtLeast(1)
                periodStart = c.startPeriod.coerceIn(1, max)
                periodEnd = c.endPeriod.coerceIn(periodStart, max)
                pickingEnd = false
            }
        }
    }
    LaunchedEffect(saved) { if (saved) nav.popBackStack() }

    val customTimeValid = (Schedule.parseClock(customTimeStart)?.let { s ->
        Schedule.parseClock(customTimeEnd)?.let { e -> e > s }
    }) == true

    /** 至少保留一个星期。 */
    fun toggleWeekday(day: Int) {
        val bit = 1 shl (day - 1)
        weekdaysMask = if (weekdaysMask and bit != 0) {
            if (weekdaysMask xor bit == 0) weekdaysMask else weekdaysMask xor bit
        } else {
            weekdaysMask or bit
        }
    }

    /**
     * 节次选择:点一节开始(单节),再点一节结束组成连续区间。
     * 点同一节两次 = 单节;已选区间内再点任一节,该节成为新的开始。
     */
    fun tapPeriod(i: Int) {
        if (!pickingEnd) {
            periodStart = i
            periodEnd = i
            pickingEnd = true
        } else {
            val (a, b) = if (i < periodStart) i to periodStart else periodStart to i
            periodStart = a
            periodEnd = b
            pickingEnd = false
        }
    }

    /** 当前选择解析出的周次计划。「自定义」一个都没勾时 `weeks` 为空(保存按钮会禁用)。 */
    val pickedWeeks = WeekSpec.decode(customWeeks)
    val weekSpec = run {
        val lo = (rangeStart.toIntOrNull() ?: 1).coerceIn(1, weekCount)
        val hi = (rangeEnd.toIntOrNull() ?: weekCount).coerceIn(1, weekCount)
        when (weekTypeIdx) {
            1 -> WeekSpec.Spec(WeekType.ODD_WEEK, minOf(lo, hi), maxOf(lo, hi))
            2 -> WeekSpec.Spec(WeekType.EVEN_WEEK, minOf(lo, hi), maxOf(lo, hi))
            3 -> WeekSpec.Spec(WeekType.CUSTOM, pickedWeeks.minOrNull() ?: 1, pickedWeeks.maxOrNull() ?: 1, pickedWeeks)
            else -> WeekSpec.everySpec(weekCount)
        }
    }
    val weekValid = weekTypeIdx != 3 || weekSpec.weeks.isNotEmpty()

    fun toggleWeek(week: Int) {
        val picked = pickedWeeks.toMutableSet()
        if (!picked.add(week)) picked.remove(week)
        customWeeks = WeekSpec.encode(picked.toList())
    }

    /** 切到「自定义」时还没勾过任何周,就把当前档位的周次铺进去当起点。 */
    fun selectWeekType(index: Int) {
        if (index == 3 && customWeeks.isBlank()) {
            customWeeks = WeekSpec.encode(WeekSpec.weeksOf(weekSpec, weekCount))
        }
        weekTypeIdx = index
    }

    fun submit() {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !weekValid) return
        val primaryWeekday = (1..7).firstOrNull { weekdaysMask and (1 shl (it - 1)) != 0 } ?: 1
        if (timeMode == 1) {
            val cs = Schedule.parseClock(customTimeStart) ?: return
            val ce = Schedule.parseClock(customTimeEnd) ?: return
            if (ce <= cs) return
            val course = Course(
                id = editing?.id ?: UUID.randomUUID().toString(),
                name = trimmed,
                teacher = teacher.trim(),
                location = location.trim(),
                weekday = primaryWeekday,
                startPeriod = 0,
                endPeriod = 0,
                weekType = weekSpec.type,
                weekStart = weekSpec.start,
                weekEnd = weekSpec.end,
                weeks = weekSpec.weeks,
                semesterId = editing?.semesterId ?: "default",
                updatedAt = System.currentTimeMillis(),
                customStartMinute = cs,
                customEndMinute = ce,
                weekdays = weekdaysMask,
                note = note.trim(),
                colorHex = colorHex.trim(),
            )
            viewModel.save(course)
        } else {
            // 时刻钉住:节次没动就沿用原来的时刻(改个名字、改个地点不该把时间带走),
            // 改了节次或新建时才按当前作息换算一次。
            val sameSection = editing != null &&
                editing.startPeriod == periodStart && editing.endPeriod == periodEnd
            val pinnedStart = if (sameSection) {
                editing?.customStartMinute
            } else {
                periods.getOrNull(periodStart - 1)?.start
            }
            val pinnedEnd = if (sameSection) {
                editing?.customEndMinute
            } else {
                periods.getOrNull(periodEnd - 1)?.end
            }
            val course = Course(
                id = editing?.id ?: UUID.randomUUID().toString(),
                name = trimmed,
                teacher = teacher.trim(),
                location = location.trim(),
                weekday = primaryWeekday,
                startPeriod = periodStart,
                endPeriod = periodEnd,
                weekType = weekSpec.type,
                weekStart = weekSpec.start,
                weekEnd = weekSpec.end,
                weeks = weekSpec.weeks,
                semesterId = editing?.semesterId ?: "default",
                updatedAt = System.currentTimeMillis(),
                customStartMinute = pinnedStart,
                customEndMinute = pinnedEnd,
                weekdays = weekdaysMask,
                note = note.trim(),
                colorHex = colorHex.trim(),
            )
            viewModel.save(course)
        }
    }

    val weekNames = listOf("一", "二", "三", "四", "五", "六", "日")
    val weekTypeNames = listOf("每周", "单周", "双周", "自定义")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(
            title = if (editing != null) "编辑课程" else "添加课程",
            onBack = { nav.popBackStack() },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = YohakuDimens.screenPadding),
        ) {
            YohakuTextField(
                value = name,
                onValueChange = { name = it },
                label = "课程名称",
                placeholder = "如 高等数学(上)",
                serifStyle = true,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            YohakuTextField(
                value = teacher,
                onValueChange = { teacher = it },
                label = "教师(选填)",
                placeholder = "如 张老师",
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            YohakuTextField(
                value = location,
                onValueChange = { location = it },
                label = "地点(选填)",
                placeholder = "如 教1-201",
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            YohakuTextField(
                value = note,
                onValueChange = { note = it },
                label = "备注(选填)",
                placeholder = "如 带课本/作业,或考试安排",
                singleLine = false,
                maxLines = 3,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            FieldLabel("星期(可多选)")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                weekNames.forEachIndexed { index, nameText ->
                    YohakuChip(
                        text = nameText,
                        selected = weekdaysMask and (1 shl index) != 0,
                        onClick = { toggleWeekday(index + 1) },
                    )
                }
            }
            Text(
                text = "已选:${(1..7).filter { weekdaysMask and (1 shl (it - 1)) != 0 }
                    .joinToString("、") { "周${weekNames[it - 1]}" }}",
                style = YohakuType.label12,
                color = colors.neutral7,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            FieldLabel("时间")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YohakuChip(
                    text = "按节次",
                    selected = timeMode == 0,
                    onClick = { timeMode = 0 },
                )
                YohakuChip(
                    text = "自定义时间",
                    selected = timeMode == 1,
                    onClick = { timeMode = 1 },
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (timeMode == 0) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    periods.indices.forEach { i ->
                        val n = i + 1
                        YohakuChip(
                            text = "$n",
                            selected = n in periodStart..periodEnd,
                            onClick = { tapPeriod(n) },
                        )
                    }
                }
                val selText = if (periodStart == periodEnd) "第 $periodStart 节" else "第 $periodStart-$periodEnd 节"
                Text(
                    text = if (pickingEnd) {
                        "已选:$selText · 再点一节作为结束"
                    } else {
                        "已选:$selText · ${Schedule.periodRange(periods, periodStart, periodEnd)}"
                    },
                    style = YohakuType.timeMono,
                    color = colors.neutral7,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    YohakuTextField(
                        value = customTimeStart,
                        onValueChange = { customTimeStart = it },
                        label = "开始时间",
                        placeholder = "18:30",
                        modifier = Modifier.weight(1f),
                    )
                    YohakuTextField(
                        value = customTimeEnd,
                        onValueChange = { customTimeEnd = it },
                        label = "结束时间",
                        placeholder = "20:00",
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = if (customTimeValid) {
                        "适合不在作息表内的课程(如晚间讲座、临时加课),不随作息变化。"
                    } else {
                        "时间格式应为 HH:MM,且结束时间需晚于开始时间。"
                    },
                    style = YohakuType.label12,
                    color = if (customTimeValid) colors.neutral7 else colors.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            FieldLabel("课程颜色")
            Text(
                text = "用预设快速区分课程，也可以输入 #RRGGBB 自定义颜色。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                CoursePalette.PRESETS.forEach { (label, hex) ->
                    val selected = colorHex.equals(hex, ignoreCase = true)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(android.graphics.Color.parseColor(hex)))
                                .clickable { colorHex = hex },
                        )
                        Text(
                            text = label,
                            style = YohakuType.label12,
                            color = if (selected) colors.neutral10 else colors.neutral7,
                        )
                    }
                }
            }
            YohakuTextField(
                value = colorHex,
                onValueChange = { value ->
                    colorHex = value.take(9)
                },
                label = "自定义色值(可选)",
                placeholder = "留空使用自动配色，如 #C56473",
                singleLine = true,
                modifier = Modifier.padding(top = 10.dp),
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            FieldLabel("周次")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                weekTypeNames.forEachIndexed { index, nameText ->
                    YohakuChip(
                        text = nameText,
                        selected = weekTypeIdx == index,
                        onClick = { selectWeekType(index) },
                    )
                }
            }
            if (weekTypeIdx == 1 || weekTypeIdx == 2) {
                Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    YohakuTextField(
                        value = rangeStart,
                        onValueChange = { rangeStart = it },
                        label = "起始周",
                        modifier = Modifier.weight(1f),
                    )
                    YohakuTextField(
                        value = rangeEnd,
                        onValueChange = { rangeEnd = it },
                        label = "结束周",
                        placeholder = "$weekCount",
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = "单/双周按学期周次算奇偶,只在起止范围内生效 —— 如「3-19 双周」= 第 4、6、…、18 周。",
                    style = YohakuType.label12,
                    color = colors.neutral7,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (weekTypeIdx == 3) {
                Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
                // 逐周勾选:等宽(不按文字自适应)才会成列对齐 —— 否则「1」和「10」宽度不同,
                // 换行位置随之参差,整块看起来就是歪的。同时补上行间距,不然换行后上下贴死。
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (1..weekCount).forEach { w ->
                        YohakuChip(
                            text = "$w",
                            selected = w in pickedWeeks,
                            onClick = { toggleWeek(w) },
                            modifier = Modifier.widthIn(min = WeekChipMinWidth),
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(
                        text = "全选",
                        style = YohakuType.copy13,
                        color = colors.accent,
                        modifier = Modifier.clickable {
                            customWeeks = WeekSpec.encode((1..weekCount).toList())
                        },
                    )
                    Text(
                        text = "清空",
                        style = YohakuType.copy13,
                        color = colors.neutral7,
                        modifier = Modifier.clickable { customWeeks = "" },
                    )
                }
            }
            Text(
                text = if (weekValid) {
                    "周次:${WeekSpec.text(weekSpec, weekCount)}"
                } else {
                    "周次:自定义至少要选一周"
                },
                style = YohakuType.label12,
                color = if (weekValid) colors.neutral7 else colors.error,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            if (editing != null) {
                Text(
                    text = "删除课程",
                    style = YohakuType.copy13,
                    color = colors.error,
                    modifier = Modifier
                        .clickable { viewModel.delete(editing!!.id) }
                        .padding(vertical = 8.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(YohakuDimens.screenPadding),
        ) {
            YohakuButton(
                text = if (editing != null) "保存修改" else "保存",
                onClick = ::submit,
                modifier = Modifier.weight(1f),
                enabled = weekValid,
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    val colors = LocalYohakuColors.current
    Text(text = text, style = YohakuType.label12, color = colors.neutral7)
    Spacer(modifier = Modifier.height(6.dp))
}

/**
 * 逐周勾选每个 chip 的最小宽度。取「两位数 + 左右 12dp 内边距」再留一点余量,
 * 于是 1..9 和 10.. 的块等宽、列能对齐;用 min 而不是固定宽,是为了字体放大后
 * 宁可放宽也不把数字裁掉(那种情况下宽度差只影响放大后的排版)。
 */
private val WeekChipMinWidth = 44.dp

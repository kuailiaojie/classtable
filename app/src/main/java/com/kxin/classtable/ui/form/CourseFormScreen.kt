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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTextField
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.domain.Schedule
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
            courseRepository.save(course)
            _saved.value = true
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            courseRepository.delete(id)
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
    val periodRows = remember(periods.size) { Schedule.bigPeriods(periods.size) }

    var name by rememberSaveable { mutableStateOf("") }
    var teacher by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var weekdaysMask by rememberSaveable { mutableIntStateOf(1) }   // 位掩码:bit(day-1)=1
    var periodIdx by rememberSaveable { mutableIntStateOf(0) }
    var weekTypeIdx by rememberSaveable { mutableIntStateOf(0) }
    var customStart by rememberSaveable { mutableStateOf("1") }
    var customEnd by rememberSaveable { mutableStateOf("16") }
    var timeMode by rememberSaveable { mutableIntStateOf(0) }      // 0=按节次 1=自定义时间
    var customTimeStart by rememberSaveable { mutableStateOf("18:30") }
    var customTimeEnd by rememberSaveable { mutableStateOf("20:00") }

    LaunchedEffect(Unit) { viewModel.load(courseId) }
    LaunchedEffect(editing) {
        editing?.let { c ->
            name = c.name
            teacher = c.teacher
            location = c.location
            note = c.note
            weekdaysMask = if (c.weekdays > 0) c.weekdays else 1 shl (c.weekday - 1)
            weekTypeIdx = WeekType.entries.indexOf(c.weekType).coerceAtLeast(0)
            customStart = c.weekStart.toString()
            customEnd = c.weekEnd.toString()
            if (c.hasCustomTime()) {
                timeMode = 1
                customTimeStart = Schedule.clockText(c.customStartMinute ?: 0)
                customTimeEnd = Schedule.clockText(c.customEndMinute ?: 0)
            } else {
                timeMode = 0
                periodIdx = periodRows
                    .indexOfFirst { it.first == c.startPeriod && it.second == c.endPeriod }
                    .coerceAtLeast(0)
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

    fun submit() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val (ws, we) = if (weekTypeIdx == 3) {
            (customStart.toIntOrNull() ?: 1) to (customEnd.toIntOrNull() ?: 16)
        } else {
            1 to 16
        }
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
                weekType = WeekType.entries[weekTypeIdx],
                weekStart = ws,
                weekEnd = we,
                semesterId = editing?.semesterId ?: "default",
                updatedAt = System.currentTimeMillis(),
                customStartMinute = cs,
                customEndMinute = ce,
                weekdays = weekdaysMask,
                note = note.trim(),
            )
            viewModel.save(course)
        } else {
            val (p1, p2) = periodRows[periodIdx]
            val course = Course(
                id = editing?.id ?: UUID.randomUUID().toString(),
                name = trimmed,
                teacher = teacher.trim(),
                location = location.trim(),
                weekday = primaryWeekday,
                startPeriod = p1,
                endPeriod = p2,
                weekType = WeekType.entries[weekTypeIdx],
                weekStart = ws,
                weekEnd = we,
                semesterId = editing?.semesterId ?: "default",
                updatedAt = System.currentTimeMillis(),
                weekdays = weekdaysMask,
                note = note.trim(),
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    periodRows.forEachIndexed { index, (p1, p2) ->
                        YohakuChip(
                            text = "$p1-$p2",
                            selected = periodIdx == index,
                            onClick = { periodIdx = index },
                        )
                    }
                }
                val (selStart, selEnd) = periodRows[periodIdx]
                Text(
                    text = "已选:第 ${selStart}-${selEnd} 节 · ${Schedule.periodRange(periods, selStart, selEnd)}",
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

            FieldLabel("周次")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                weekTypeNames.forEachIndexed { index, nameText ->
                    YohakuChip(
                        text = nameText,
                        selected = weekTypeIdx == index,
                        onClick = { weekTypeIdx = index },
                    )
                }
            }
            if (weekTypeIdx == 3) {
                Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    YohakuTextField(
                        value = customStart,
                        onValueChange = { customStart = it },
                        label = "起始周",
                        modifier = Modifier.weight(1f),
                    )
                    YohakuTextField(
                        value = customEnd,
                        onValueChange = { customEnd = it },
                        label = "结束周",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
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

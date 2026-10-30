package com.kxin.classtable.ui.agenda

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuConfirmDialog
import com.kxin.classtable.design.YohakuDatePicker
import com.kxin.classtable.design.YohakuDialog
import com.kxin.classtable.design.YohakuDialogAction
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuMotion
import com.kxin.classtable.design.YohakuSegmentedControl
import com.kxin.classtable.design.YohakuSwitch
import com.kxin.classtable.design.YohakuTextField
import com.kxin.classtable.design.YohakuTimePicker
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.rememberYohakuHaptics
import com.kxin.classtable.design.yohakuTouchTarget
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AgendaCategory
import com.kxin.classtable.domain.model.AgendaEvent
import com.kxin.classtable.domain.model.AgendaKind
import com.kxin.classtable.domain.model.AgendaPriority
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.kind
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 新建 / 编辑日程与倒计时:整页表单。
 *
 * 针对「日程」与「倒计时」两种心智深度区分：
 * - 顶部胶囊提供直观的「📅 日程」与「⏳ 倒计时」模式切换
 * - 「日程」心智：安排时段，聚焦具体起止时刻与日程时长
 * - 「倒计时」心智：锚定目标，聚焦截止日期、实时剩余天数预览与备考提醒
 */
@Composable
fun AgendaFormScreen(
    nav: NavHostController,
    eventId: String?,
    defaultDateEpoch: Long,
    defaultCategory: AgendaCategory? = null,
    defaultCourseId: String? = null,
    defaultStartMinute: Int = -1,
    defaultEndMinute: Int = -1,
    viewModel: AgendaViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val events by viewModel.events.collectAsStateWithLifecycle()
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val editing = remember(eventId, events) {
        eventId?.let { id -> events.firstOrNull { it.id == id } }
    }
    val defaultDate = if (defaultDateEpoch > 0) {
        LocalDate.ofEpochDay(defaultDateEpoch)
    } else {
        LocalDate.now()
    }
    val initialKind = (editing?.category ?: defaultCategory ?: AgendaCategory.TODO).kind
    var currentKind by remember(editing?.id, initialKind) { mutableStateOf(initialKind) }

    // 保存/删除成功后才返回:失败留在表单并弹窗说明,而不是静默返回
    androidx.compose.runtime.LaunchedEffect(saved) { if (saved) nav.popBackStack() }
    error?.let { message ->
        YohakuDialog(
            onDismissRequest = { viewModel.clearError() },
            title = "操作未完成",
            actions = {
                YohakuDialogAction(text = "知道了", accent = true, onClick = { viewModel.clearError() })
            },
        ) {
            Text(text = message, style = YohakuType.copy14, color = colors.neutral9)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = YohakuDimens.maxFormWidth)
                .fillMaxWidth(),
        ) {
            YohakuTopBar(
                title = if (eventId != null) "编辑${currentKind.label}" else "新建${currentKind.label}",
                onBack = { nav.popBackStack() },
            )
            if (eventId != null && editing == null) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "载入中…",
                        style = YohakuType.copy14,
                        color = colors.neutral6,
                    )
                }
            } else {
                AgendaFormBody(
                    initial = editing,
                    kind = currentKind,
                    onKindChange = { currentKind = it },
                    defaultDate = defaultDate,
                    defaultCategory = defaultCategory,
                    courses = courses,
                    defaultCourseId = defaultCourseId,
                    defaultStartMinute = defaultStartMinute,
                    defaultEndMinute = defaultEndMinute,
                    onSave = { viewModel.save(it) },
                    onDelete = { viewModel.delete(it) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgendaFormBody(
    initial: AgendaEvent?,
    kind: AgendaKind,
    onKindChange: (AgendaKind) -> Unit,
    defaultDate: LocalDate,
    defaultCategory: AgendaCategory?,
    courses: List<Course> = emptyList(),
    defaultCourseId: String? = null,
    defaultStartMinute: Int = -1,
    defaultEndMinute: Int = -1,
    onSave: (AgendaEvent) -> Unit,
    onDelete: (String) -> Unit,
) {
    val colors = LocalYohakuColors.current
    val haptics = rememberYohakuHaptics()
    val isCountdown = kind == AgendaKind.COUNTDOWN

    val startInit = initial?.startLocal()
        ?: if (defaultStartMinute >= 0) defaultDate.atTime(defaultStartMinute / 60, defaultStartMinute % 60)
        else defaultDate.atTime(9, 0)
    val endInit = initial?.endLocal()
        ?: if (defaultEndMinute >= 0) defaultDate.atTime(defaultEndMinute / 60, defaultEndMinute % 60)
        else defaultDate.atTime(10, 0)

    val key = initial?.id
    var title by rememberSaveable(key) { mutableStateOf(initial?.title ?: "") }
    var category by rememberSaveable(key, kind) {
        mutableStateOf(
            initial?.takeIf { it.category.kind == kind }?.category
                ?: defaultCategory?.takeIf { it.kind == kind }
                ?: if (isCountdown) AgendaCategory.EXAM else AgendaCategory.TODO
        )
    }
    var priority by rememberSaveable(key) { mutableStateOf(initial?.priority ?: AgendaPriority.NONE) }
    var courseId by rememberSaveable(key) { mutableStateOf(initial?.courseId ?: defaultCourseId) }
    var completed by rememberSaveable(key) { mutableStateOf(initial?.completed ?: false) }
    var allDay by rememberSaveable(key) { mutableStateOf(initial?.allDay ?: isCountdown) }
    var location by rememberSaveable(key) { mutableStateOf(initial?.location ?: "") }
    var note by rememberSaveable(key) { mutableStateOf(initial?.note ?: "") }
    var remindEnabled by rememberSaveable(key) { mutableStateOf(initial?.remindEnabled ?: false) }
    var remindLeadMinutes by rememberSaveable(key, kind) {
        mutableIntStateOf(
            initial?.remindLeadMinutes
                ?: if (isCountdown) 1440 else 10 // 倒计时默认提前1天(1440分)，日程默认提前10分钟
        )
    }
    var startDateEpoch by rememberSaveable(key) { mutableLongStateOf(startInit.toLocalDate().toEpochDay()) }
    var startMinute by rememberSaveable(key) { mutableIntStateOf(startInit.hour * 60 + startInit.minute) }
    var endDateEpoch by rememberSaveable(key) { mutableLongStateOf(endInit.toLocalDate().toEpochDay()) }
    var endMinute by rememberSaveable(key) { mutableIntStateOf(endInit.hour * 60 + endInit.minute) }

    val startDate = LocalDate.ofEpochDay(startDateEpoch)
    val endDate = LocalDate.ofEpochDay(endDateEpoch)

    var pickStartDate by remember { mutableStateOf(false) }
    var pickStartTime by remember { mutableStateOf(false) }
    var pickEndDate by remember { mutableStateOf(false) }
    var pickEndTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val timeValid = if (isCountdown) {
        allDay || endMinute >= startMinute
    } else {
        allDay || endDate > startDate || (endDate == startDate && endMinute > startMinute)
    }
    val valid = title.isNotBlank() && timeValid
    val blockingHint = when {
        title.isBlank() -> "请填写${kind.label}标题"
        !timeValid -> "结束时间需晚于开始时间"
        else -> null
    }

    fun startAtMillis(): Long = if (allDay) {
        startDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } else {
        startDate.atStartOfDay(ZoneId.systemDefault()).plusMinutes(startMinute.toLong())
            .toInstant().toEpochMilli()
    }

    fun endAtMillis(): Long = if (allDay) {
        endDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
    } else {
        endDate.atStartOfDay(ZoneId.systemDefault()).plusMinutes(endMinute.toLong())
            .toInstant().toEpochMilli()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = YohakuDimens.screenPadding),
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // 1. 顶层类型分段：清晰表达「日程」与「倒计时」
            YohakuSegmentedControl(
                options = listOf("📅 日程", "⏳ 倒计时"),
                selectedIndex = if (isCountdown) 1 else 0,
                onSelect = { index ->
                    val newKind = if (index == 0) AgendaKind.SCHEDULE else AgendaKind.COUNTDOWN
                    if (newKind != kind) {
                        haptics.select()
                        onKindChange(newKind)
                        if (category.kind != newKind) {
                            category = if (newKind == AgendaKind.COUNTDOWN) AgendaCategory.EXAM else AgendaCategory.TODO
                        }
                        if (newKind == AgendaKind.COUNTDOWN) {
                            allDay = true
                            endDateEpoch = startDateEpoch
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))

            // 2. 区分度极高的标题输入：名称与占位明确分工
            YohakuTextField(
                value = title,
                onValueChange = { title = it },
                label = if (isCountdown) "目标名称" else "日程名称",
                placeholder = if (isCountdown) "如 期末高数考试、开题答辩、作业截止、六级" else "如 班级例会、图书借还、自习讨论",
                serifStyle = true,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            // 3. 分类标签与归属解释
            FieldLabel("分类")
            val categories = remember(kind) { AgendaCategory.entries.filter { it.kind == kind } }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                categories.forEach { c ->
                    YohakuChip(
                        text = c.label,
                        selected = category == c,
                        onClick = { category = c },
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isCountdown) {
                    "收录关键考试、作业或截止节点，将在「倒计时」中按剩余天数置顶倒数。"
                } else {
                    "安排具体时间要进行的事务，将在「日程」时间线中按时刻排布。"
                },
                style = YohakuType.label12,
                color = colors.neutral6,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            // 4. 关联课程
            val distinctCourses = remember(courses) { courses.distinctBy { it.name } }
            if (distinctCourses.isNotEmpty()) {
                FieldLabel("关联课程(选填)")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    YohakuChip(
                        text = "不关联",
                        selected = courseId == null,
                        onClick = { courseId = null },
                    )
                    distinctCourses.forEach { c ->
                        val isSelected = courseId == c.id || (courseId != null && courses.find { it.id == courseId }?.name == c.name)
                        YohakuChip(
                            text = c.name,
                            selected = isSelected,
                            onClick = {
                                courseId = if (isSelected) null else c.id
                            },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
            }

            // 5. 时间配置：彻底区分「倒计时目标日」与「日程起止时间段」
            if (isCountdown) {
                FieldLabel("目标日期")
                PickerField(
                    label = "截止 / 考试日期",
                    value = dateText(startDate),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { pickStartDate = true },
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 核心视觉反馈：实时倒计时卡片预览，让用户立刻看到倒数天数
                val today = remember { LocalDate.now() }
                val days = daysUntil(startDate, today)
                val cardBg = when {
                    days < 0L -> colors.neutral2.copy(alpha = 0.6f)
                    days <= 3L -> colors.accent.copy(alpha = 0.12f)
                    else -> colors.raised
                }
                val cardBorder = when {
                    days < 0L -> BorderStroke(1.dp, colors.line.copy(alpha = 0.5f))
                    days <= 3L -> BorderStroke(1.dp, colors.accent.copy(alpha = 0.35f))
                    else -> BorderStroke(1.dp, colors.line)
                }
                val badgeColor = if (days in 0L..3L) colors.accent else colors.neutral10
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(YohakuDimens.radiusCard),
                    color = cardBg,
                    border = cardBorder,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🎯 倒计时目标日 · ${dateText(startDate)}",
                                style = YohakuType.label12,
                                color = colors.neutral7,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val countdownStatusText = when {
                                days > 0L -> "距离今天还有 $days 天"
                                days == 0L -> "今天到期 · 进行中"
                                else -> "已截止 ${kotlin.math.abs(days)} 天"
                            }
                            Text(
                                text = countdownStatusText,
                                style = YohakuType.title20,
                                color = badgeColor,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(YohakuDimens.gapTight))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "全天目标 (无需具体时刻)",
                        style = YohakuType.copy15,
                        color = colors.neutral9,
                        modifier = Modifier.weight(1f),
                    )
                    YohakuSwitch(
                        checked = allDay,
                        onCheckedChange = { allDay = it },
                    )
                }
                if (!allDay) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PickerField(
                            label = "开始时刻",
                            value = Schedule.clockText(startMinute),
                            modifier = Modifier.weight(1f),
                            onClick = { pickStartTime = true },
                        )
                        PickerField(
                            label = "结束时刻",
                            value = Schedule.clockText(endMinute),
                            modifier = Modifier.weight(1f),
                            onClick = { pickEndTime = true },
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "全天",
                        style = YohakuType.copy15,
                        color = colors.neutral9,
                        modifier = Modifier.weight(1f),
                    )
                    YohakuSwitch(
                        checked = allDay,
                        onCheckedChange = { allDay = it },
                    )
                }
                Spacer(modifier = Modifier.height(YohakuDimens.gapTight))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PickerField(
                        label = "开始日期",
                        value = dateText(startDate),
                        modifier = Modifier.weight(if (allDay) 1f else 1.4f),
                        onClick = { pickStartDate = true },
                    )
                    if (!allDay) {
                        PickerField(
                            label = "时间",
                            value = Schedule.clockText(startMinute),
                            modifier = Modifier.weight(1f),
                            onClick = { pickStartTime = true },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PickerField(
                        label = "结束日期",
                        value = dateText(endDate),
                        modifier = Modifier.weight(if (allDay) 1f else 1.4f),
                        onClick = { pickEndDate = true },
                    )
                    if (!allDay) {
                        PickerField(
                            label = "时间",
                            value = Schedule.clockText(endMinute),
                            modifier = Modifier.weight(1f),
                            onClick = { pickEndTime = true },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (timeValid) {
                        "日程时长 ${formatDurationText((endAtMillis() - startAtMillis()) / 60_000)}"
                    } else {
                        "结束时间需要晚于开始时间。"
                    },
                    style = YohakuType.label12,
                    color = if (timeValid) colors.neutral7 else colors.error,
                )
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            // 6. 地点与备注
            YohakuTextField(
                value = location,
                onValueChange = { location = it },
                label = if (isCountdown) "地点 / 考场(选填)" else "地点(选填)",
                placeholder = if (isCountdown) "如 第一教学楼 201 考场" else "如 教1-201",
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            YohakuTextField(
                value = note,
                onValueChange = { note = it },
                label = "备注(选填)",
                placeholder = if (isCountdown) "如 考试范围、准考证号、文具要求" else "如 带课本 / 讨论议题",
                singleLine = false,
                maxLines = 3,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            // 7. 优先级
            FieldLabel("优先级")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AgendaPriority.entries.forEach { p ->
                    YohakuChip(
                        text = p.label,
                        selected = priority == p,
                        onClick = { priority = p },
                    )
                }
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            // 8. 区分化的提醒选项（倒计时按天备考，日程按分钟临近准备）
            FieldLabel("提醒")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "开启提醒",
                    style = YohakuType.copy15,
                    color = colors.neutral9,
                    modifier = Modifier.weight(1f),
                )
                YohakuSwitch(
                    checked = remindEnabled,
                    onCheckedChange = { remindEnabled = it },
                )
            }
            AnimatedVisibility(
                visible = remindEnabled,
                enter = fadeIn(YohakuMotion.tween(YohakuMotion.durBase)),
                exit = fadeOut(YohakuMotion.tween(YohakuMotion.durFast)),
            ) {
                Column {
                    Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
                    val remindOptions = if (isCountdown) {
                        REMIND_COUNTDOWN_OPTIONS
                    } else {
                        REMIND_SCHEDULE_OPTIONS
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        remindOptions.forEach { (minutes, label) ->
                            YohakuChip(
                                text = label,
                                selected = remindLeadMinutes == minutes,
                                onClick = { remindLeadMinutes = minutes },
                            )
                        }
                    }
                    if (allDay) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isCountdown) {
                                "全天倒计时提醒将于到期日当天或提前天数的固定时刻（默认 09:00）发送通知。"
                            } else {
                                "「全天」日程在当天设置里的时刻提醒，可在「设置 → 提醒 → 日程提醒」修改。"
                            },
                            style = YohakuType.label12,
                            color = colors.neutral7,
                        )
                    }
                }
            }

            if (initial != null) {
                Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
                Text(
                    text = if (isCountdown) "删除倒计时" else "删除日程",
                    style = YohakuType.copy13,
                    color = colors.error,
                    modifier = Modifier
                        .yohakuTouchTarget()
                        .clickable { confirmDelete = true }
                        .padding(vertical = 8.dp),
                )
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(YohakuDimens.screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!valid && blockingHint != null) {
                Text(
                    text = blockingHint,
                    style = YohakuType.label12,
                    color = colors.neutral7,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            YohakuButton(
                text = if (initial != null) {
                    if (isCountdown) "保存倒计时" else "保存日程"
                } else {
                    if (isCountdown) "创建倒计时" else "创建日程"
                },
                onClick = {
                    if (valid) {
                        onSave(
                            AgendaEvent(
                                id = initial?.id ?: UUID.randomUUID().toString(),
                                title = title.trim(),
                                category = category,
                                startAt = startAtMillis(),
                                endAt = endAtMillis(),
                                allDay = allDay,
                                location = location.trim(),
                                note = note.trim(),
                                priority = priority,
                                remindEnabled = remindEnabled,
                                remindLeadMinutes = remindLeadMinutes,
                                updatedAt = initial?.updatedAt ?: 0L,
                                courseId = courseId,
                                completed = completed,
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = valid,
            )
        }
    }

    if (pickStartDate) {
        YohakuDatePicker(
            initialEpochDay = startDateEpoch,
            onPick = {
                startDateEpoch = it
                if (isCountdown || endDateEpoch < it) {
                    endDateEpoch = it
                }
                pickStartDate = false
            },
            onClear = { pickStartDate = false },
            onDismiss = { pickStartDate = false },
        )
    }
    if (pickStartTime) {
        YohakuTimePicker(
            title = if (isCountdown) "开始时刻" else "开始时间",
            initial = Schedule.clockText(startMinute),
            onConfirm = {
                pickStartTime = false
                Schedule.parseClock(it)?.let { m ->
                    startMinute = m
                    if (endDateEpoch == startDateEpoch && endMinute < m) {
                        endMinute = (m + 60).coerceAtMost(23 * 60 + 59)
                    }
                }
            },
            onDismiss = { pickStartTime = false },
        )
    }
    if (pickEndDate) {
        YohakuDatePicker(
            initialEpochDay = endDateEpoch,
            onPick = {
                endDateEpoch = it
                if (startDateEpoch > it) startDateEpoch = it
                pickEndDate = false
            },
            onClear = { pickEndDate = false },
            onDismiss = { pickEndDate = false },
        )
    }
    if (pickEndTime) {
        YohakuTimePicker(
            title = if (isCountdown) "结束时刻" else "结束时间",
            initial = Schedule.clockText(endMinute),
            onConfirm = {
                pickEndTime = false
                Schedule.parseClock(it)?.let { m -> endMinute = m }
            },
            onDismiss = { pickEndTime = false },
        )
    }

    if (confirmDelete && initial != null) {
        YohakuConfirmDialog(
            title = if (isCountdown) "删除倒计时" else "删除日程",
            message = "「${initial.title}」会被删除,且无法撤销。",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                haptics.select()
                confirmDelete = false
                onDelete(initial.id)
            },
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    val colors = LocalYohakuColors.current
    Text(text = text, style = YohakuType.label12, color = colors.neutral7)
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun PickerField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    val shape = RoundedCornerShape(YohakuDimens.radiusControl)
    Column(modifier = modifier) {
        Text(text = label, style = YohakuType.label12, color = colors.neutral7)
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.neutral2)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(text = value, style = YohakuType.copy14, color = colors.neutral9)
        }
    }
}

/** 日程提醒的提前量选项(聚焦临近准备)。 */
private val REMIND_SCHEDULE_OPTIONS = listOf(
    0 to "准点",
    5 to "5 分钟",
    10 to "10 分钟",
    15 to "15 分钟",
    30 to "30 分钟",
    60 to "1 小时",
)

/** 倒计时提醒的提前量选项(聚焦中长期复习与备考)。 */
private val REMIND_COUNTDOWN_OPTIONS = listOf(
    0 to "当天提醒",
    1440 to "提前 1 天",
    2880 to "提前 2 天",
    4320 to "提前 3 天",
    10080 to "提前 1 周",
)

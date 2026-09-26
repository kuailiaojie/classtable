package com.kxin.classtable.ui.agenda

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuDatePicker
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuSwitch
import com.kxin.classtable.design.YohakuTextField
import com.kxin.classtable.design.YohakuTimePicker
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.domain.model.AgendaCategory
import com.kxin.classtable.domain.model.AgendaEvent
import com.kxin.classtable.domain.model.AgendaPriority
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 新建 / 编辑日程:整页表单(与「添加课程」同一套 —— 顶部返回 + 内容滚动 + 底部按钮钉住)。
 *
 * 之所以不做成底部弹层:弹层走 Compose `Dialog`,其内容在部分版本里拿不到「一屏」的高度约束,
 * 面板会被撑到屏幕外、底部按钮掉下去。整页没有这个问题 —— 内容就排在导航栈自己的 Box 里。
 *
 * 字段:标题、分类、全天开关、开始与结束(日期 + 时间)、地点、备注、优先级、提醒;编辑态可删除。
 */
@Composable
fun AgendaFormScreen(
    nav: NavHostController,
    eventId: String?,
    defaultDateEpoch: Long,
    defaultCategory: AgendaCategory? = null,
    viewModel: AgendaViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val events by viewModel.events.collectAsStateWithLifecycle()
    val editing = remember(eventId, events) {
        eventId?.let { id -> events.firstOrNull { it.id == id } }
    }
    val defaultDate = if (defaultDateEpoch > 0) {
        LocalDate.ofEpochDay(defaultDateEpoch)
    } else {
        LocalDate.now()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(
            title = if (eventId != null) "编辑日程" else "新建日程",
            onBack = { nav.popBackStack() },
        )
        if (eventId != null && editing == null) {
            // 编辑态:等目标条目从本地库出来再建表单,避免把「还没读到」当成新建
            Spacer(modifier = Modifier.weight(1f))
        } else {
            AgendaFormBody(
                initial = editing,
                defaultDate = defaultDate,
                defaultCategory = defaultCategory,
                onSave = {
                    viewModel.save(it)
                    nav.popBackStack()
                },
                onDelete = {
                    viewModel.delete(it)
                    nav.popBackStack()
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgendaFormBody(
    initial: AgendaEvent?,
    defaultDate: LocalDate,
    defaultCategory: AgendaCategory?,
    onSave: (AgendaEvent) -> Unit,
    onDelete: (String) -> Unit,
) {
    val colors = LocalYohakuColors.current
    val startInit = initial?.startLocal() ?: defaultDate.atTime(9, 0)
    val endInit = initial?.endLocal() ?: defaultDate.atTime(10, 0)

    // 以条目 id 为键:编辑态读到条目时初始化一次,之后不再被上游刷新冲掉
    val key = initial?.id
    var title by remember(key) { mutableStateOf(initial?.title ?: "") }
    var category by remember(key) {
        mutableStateOf(initial?.category ?: defaultCategory ?: AgendaCategory.TODO)
    }
    var priority by remember(key) { mutableStateOf(initial?.priority ?: AgendaPriority.NONE) }
    var allDay by remember(key) { mutableStateOf(initial?.allDay ?: false) }
    var location by remember(key) { mutableStateOf(initial?.location ?: "") }
    var note by remember(key) { mutableStateOf(initial?.note ?: "") }
    var remindEnabled by remember(key) { mutableStateOf(initial?.remindEnabled ?: false) }
    var remindLeadMinutes by remember(key) { mutableIntStateOf(initial?.remindLeadMinutes ?: 10) }
    var startDate by remember(key) { mutableStateOf(startInit.toLocalDate()) }
    var startMinute by remember(key) { mutableIntStateOf(startInit.hour * 60 + startInit.minute) }
    var endDate by remember(key) { mutableStateOf(endInit.toLocalDate()) }
    var endMinute by remember(key) { mutableIntStateOf(endInit.hour * 60 + endInit.minute) }

    var pickStartDate by remember { mutableStateOf(false) }
    var pickStartTime by remember { mutableStateOf(false) }
    var pickEndDate by remember { mutableStateOf(false) }
    var pickEndTime by remember { mutableStateOf(false) }

    val timeValid = allDay || endDate > startDate || (endDate == startDate && endMinute > startMinute)
    val valid = title.isNotBlank() && timeValid

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
            YohakuTextField(
                value = title,
                onValueChange = { title = it },
                label = "日程标题",
                placeholder = "如 提交高数作业",
                serifStyle = true,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            FieldLabel("分类")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AgendaCategory.entries.forEach { c ->
                    YohakuChip(
                        text = c.label,
                        selected = category == c,
                        onClick = { category = c },
                    )
                }
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

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
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

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
                        value = clockText(startMinute),
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
                        value = clockText(endMinute),
                        modifier = Modifier.weight(1f),
                        onClick = { pickEndTime = true },
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (timeValid) {
                    "时长 ${formatDurationText((endAtMillis() - startAtMillis()) / 60_000)}"
                } else {
                    "结束时间需要晚于开始时间。"
                },
                style = YohakuType.label12,
                color = if (timeValid) colors.neutral7 else colors.error,
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
                placeholder = "如 带课本 / 考试范围",
                singleLine = false,
                maxLines = 3,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

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

            FieldLabel("提醒")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YohakuChip(
                    text = "提醒",
                    selected = remindEnabled,
                    onClick = { remindEnabled = true },
                )
                YohakuChip(
                    text = "不提醒",
                    selected = !remindEnabled,
                    onClick = { remindEnabled = false },
                )
            }
            if (remindEnabled) {
                Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
                if (allDay) {
                    Text(
                        text = "「全天」日程在当天设置里的时刻提醒,可在「设置 → 提醒 → 日程提醒」修改。",
                        style = YohakuType.label12,
                        color = colors.neutral7,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        REMIND_LEAD_OPTIONS.forEach { (minutes, label) ->
                            YohakuChip(
                                text = label,
                                selected = remindLeadMinutes == minutes,
                                onClick = { remindLeadMinutes = minutes },
                            )
                        }
                    }
                }
            }

            if (initial != null) {
                Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
                Text(
                    text = "删除日程",
                    style = YohakuType.copy13,
                    color = colors.error,
                    modifier = Modifier
                        .clickable { onDelete(initial.id) }
                        .padding(vertical = 8.dp),
                )
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(YohakuDimens.screenPadding),
        ) {
            YohakuButton(
                text = if (initial != null) "保存修改" else "创建日程",
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
            initialEpochDay = startDate.toEpochDay(),
            onPick = { pickStartDate = false; startDate = LocalDate.ofEpochDay(it) },
            onClear = { pickStartDate = false },
            onDismiss = { pickStartDate = false },
        )
    }
    if (pickEndDate) {
        YohakuDatePicker(
            initialEpochDay = endDate.toEpochDay(),
            onPick = { pickEndDate = false; endDate = LocalDate.ofEpochDay(it) },
            onClear = { pickEndDate = false },
            onDismiss = { pickEndDate = false },
        )
    }
    if (pickStartTime) {
        YohakuTimePicker(
            title = "开始时间",
            initial = clockText(startMinute),
            onConfirm = { pickStartTime = false; ScheduleParse.toMinute(it)?.let { m -> startMinute = m } },
            onDismiss = { pickStartTime = false },
        )
    }
    if (pickEndTime) {
        YohakuTimePicker(
            title = "结束时间",
            initial = clockText(endMinute),
            onConfirm = { pickEndTime = false; ScheduleParse.toMinute(it)?.let { m -> endMinute = m } },
            onDismiss = { pickEndTime = false },
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

private fun clockText(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

/** 日程提醒的提前量选项(与课程提醒同一套档位,便于理解)。 */
private val REMIND_LEAD_OPTIONS = listOf(
    0 to "准点",
    5 to "5 分钟",
    10 to "10 分钟",
    15 to "15 分钟",
    30 to "30 分钟",
    60 to "1 小时",
)

/** "HH:MM" → 分钟;非法返回 null。 */
private object ScheduleParse {
    fun toMinute(text: String): Int? {
        val parts = text.trim().split(":")
        if (parts.size < 2) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val m = parts[1].trim().toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }
}

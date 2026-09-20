package com.kxin.classtable.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuDatePicker
import com.kxin.classtable.design.YohakuDialog
import com.kxin.classtable.design.YohakuDialogAction
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuOutlineButton
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.domain.Adjustments
import com.kxin.classtable.domain.ScheduleAdjustment
import com.kxin.classtable.domain.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class AdjustmentsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun save(items: List<ScheduleAdjustment>) = viewModelScope.launch {
        settingsRepository.setScheduleAdjustments(Adjustments.encode(items))
    }
}

/**
 * 调休课表:给**某一天**指定「停课」或「补上另一天的课」。
 *
 * 关键语义和课表本身的模型一致 —— 课程不会被搬动。调休改的只是「这一天该取哪一天的课」,
 * 所以周六补周四的课只是让周六去查周四那天(第几周 + 星期几)的课程;撤销调休即恢复原样。
 */
@Composable
fun AdjustmentsScreen(
    nav: NavHostController,
    viewModel: AdjustmentsViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val items = remember(settings.scheduleAdjustments) {
        Adjustments.decode(settings.scheduleAdjustments)
    }
    var draft by remember { mutableStateOf<Draft?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(title = "调休课表", onBack = { nav.popBackStack() })

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = YohakuDimens.screenPadding),
        ) {
            Text(
                text = "停课 = 当天不上课;补课 = 当天按「原课程日期」那一天的课表上课。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Text(
                text = "课程本身不会被移动:调休只是改变「这一天取哪天的课」,随时可以撤销。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))

            if (items.isEmpty()) {
                Text(
                    text = "还没有调休安排。放假、调休补课都写在这里。",
                    style = YohakuType.copy13,
                    color = colors.neutral6,
                )
            }
            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = dateLabel(item.date.toEpochDay()),
                            style = YohakuType.copy15,
                            color = colors.neutral10,
                        )
                        Text(
                            text = item.summary(),
                            style = YohakuType.label12,
                            color = colors.neutral7,
                        )
                    }
                    Text(
                        text = "修改",
                        style = YohakuType.label12,
                        color = colors.accent,
                        modifier = Modifier
                            .clickable { draft = Draft.of(item) }
                            .padding(8.dp),
                    )
                    Text(
                        text = "删除",
                        style = YohakuType.label12,
                        color = colors.error,
                        modifier = Modifier
                            .clickable { viewModel.save(items - item) }
                            .padding(8.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.neutral3),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            YohakuOutlineButton(
                text = "＋ 新增调休日",
                onClick = { draft = Draft.new() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
        }
    }

    draft?.let { current ->
        AdjustmentDialog(
            draft = current,
            onDraftChange = { draft = it },
            onSave = {
                val saved = current.toAdjustment() ?: return@AdjustmentDialog
                viewModel.save((items.filter { it.date != saved.date } + saved).sortedBy { it.date })
                draft = null
            },
            onDismiss = { draft = null },
        )
    }
}

/** 编辑中的一条安排:日期与来源日期用 epochDay 存,0 = 未选。 */
private data class Draft(
    val date: Long,
    val rest: Boolean,
    val source: Long,
    val label: String,
) {
    fun toAdjustment(): ScheduleAdjustment? {
        if (date <= 0L) return null
        if (rest) return ScheduleAdjustment(LocalDate.ofEpochDay(date), null, label)
        if (source <= 0L) return null
        return ScheduleAdjustment(LocalDate.ofEpochDay(date), LocalDate.ofEpochDay(source), label)
    }

    companion object {
        fun of(item: ScheduleAdjustment) = Draft(
            date = item.date.toEpochDay(),
            rest = item.isRest,
            source = item.sourceDate?.toEpochDay() ?: 0L,
            label = item.label,
        )

        fun new() = Draft(date = LocalDate.now().toEpochDay(), rest = true, source = 0L, label = "")
    }
}

@Composable
private fun AdjustmentDialog(
    draft: Draft,
    onDraftChange: (Draft) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    var pickingDate by remember { mutableStateOf(false) }
    var pickingSource by remember { mutableStateOf(false) }
    val valid = draft.toAdjustment() != null

    if (pickingDate) {
        YohakuDatePicker(
            initialEpochDay = draft.date,
            onPick = { onDraftChange(draft.copy(date = it)); pickingDate = false },
            onClear = { pickingDate = false },
            onDismiss = { pickingDate = false },
        )
    }
    if (pickingSource) {
        YohakuDatePicker(
            initialEpochDay = draft.source.takeIf { it > 0L },
            onPick = { onDraftChange(draft.copy(source = it)); pickingSource = false },
            onClear = { onDraftChange(draft.copy(source = 0L)); pickingSource = false },
            onDismiss = { pickingSource = false },
        )
    }

    YohakuDialog(
        onDismissRequest = onDismiss,
        title = "调休安排",
        actions = {
            YohakuDialogAction(text = "取消", onClick = onDismiss)
            YohakuDialogAction(
                text = "保存",
                accent = true,
                onClick = { if (valid) onSave() },
            )
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YohakuChip(
                text = "停课",
                selected = draft.rest,
                onClick = { onDraftChange(draft.copy(rest = true)) },
            )
            YohakuChip(
                text = "补课",
                selected = !draft.rest,
                onClick = { onDraftChange(draft.copy(rest = false)) },
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        DraftRow(
            label = if (draft.rest) "停课日期" else "补课日期",
            value = dateLabel(draft.date),
        ) { pickingDate = true }
        if (!draft.rest) {
            Spacer(modifier = Modifier.height(8.dp))
            DraftRow(
                label = "原课程日期",
                value = draft.source.takeIf { it > 0L }?.let { dateLabel(it) } ?: "未选择",
            ) { pickingSource = true }
            Text(
                text = "当天按原课程日期那一天的课表上课(那一周的星期几)。",
                style = YohakuType.label12,
                color = colors.neutral7,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (!valid) {
            Text(
                text = "补课需要选择原课程日期。",
                style = YohakuType.label12,
                color = colors.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun DraftRow(label: String, value: String, onClick: () -> Unit) {
    val colors = LocalYohakuColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = YohakuType.label12, color = colors.neutral7, modifier = Modifier.width(88.dp))
        Text(text = value, style = YohakuType.timeMono, color = colors.neutral10, modifier = Modifier.weight(1f))
        Text(text = "选择", style = YohakuType.label12, color = colors.accent)
    }
}

/** "9/11 周五" */
private fun dateLabel(epochDay: Long): String {
    val d = LocalDate.ofEpochDay(epochDay)
    return "${d.monthValue}/${d.dayOfMonth} 周${"一二三四五六日"[d.dayOfWeek.value - 1]}"
}

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
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuDatePicker
import com.kxin.classtable.design.YohakuDialog
import com.kxin.classtable.design.YohakuDialogAction
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuOutlineButton
import com.kxin.classtable.design.YohakuTextField
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.domain.Adjustments
import com.kxin.classtable.domain.HolidayClient
import com.kxin.classtable.domain.HolidayPlan
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.ScheduleAdjustment
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.planHolidays
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** 预览里的一条补班日:[source] 是建议的原课程日期,学校细则可能不同,可以改。 */
data class MakeupReview(val date: LocalDate, val source: LocalDate?, val selected: Boolean)

/** 预览里的一个假期:放假区间是否采用 + 它下面各条补班日。 */
data class HolidayReview(
    val plan: HolidayPlan,
    val restSelected: Boolean,
    val makeups: List<MakeupReview>,
)

@HiltViewModel
class AdjustmentsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _reviews = MutableStateFlow<List<HolidayReview>?>(null)
    val reviews: StateFlow<List<HolidayReview>?> = _reviews.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun save(items: List<ScheduleAdjustment>) = viewModelScope.launch {
        settingsRepository.setScheduleAdjustments(Adjustments.encode(items))
    }

    /**
     * 抓取某年的节假日并生成补课建议。
     *
     * **只生成预览,不写任何数据** —— 采用与否由用户在页面上逐条勾选后决定。
     * 学期外的日期会被过滤掉(没设开学日时无从判断,保留全部)。
     */
    fun fetchHolidays(year: Int, startDay: Long, weekCount: Int) = viewModelScope.launch {
        _loading.value = true
        _error.value = null
        runCatching { planHolidays(HolidayClient.fetch(year)) }
            .onSuccess { plans ->
                val inTerm = plans.mapNotNull { plan ->
                    val rests = plan.restDates.filter {
                        Schedule.inTerm(it.toEpochDay(), startDay, weekCount)
                    }
                    val makeups = plan.makeups.filter {
                        Schedule.inTerm(it.date.toEpochDay(), startDay, weekCount)
                    }
                    if (rests.isEmpty() && makeups.isEmpty()) null else plan.copy(restDates = rests, makeups = makeups)
                }
                if (inTerm.isEmpty()) {
                    _reviews.value = null
                    _error.value = "该年份没有落在学期内的节假日"
                } else {
                    _reviews.value = inTerm.map { plan ->
                        HolidayReview(
                            plan = plan,
                            restSelected = true,
                            makeups = plan.makeups.map { MakeupReview(it.date, it.source, selected = true) },
                        )
                    }
                }
            }
            .onFailure { e ->
                _reviews.value = null
                _error.value = e.message ?: "获取失败,请稍后重试"
            }
        _loading.value = false
    }

    fun updateReviews(transform: (List<HolidayReview>) -> List<HolidayReview>) {
        _reviews.value = _reviews.value?.let(transform)
    }

    fun clearReviews() {
        _reviews.value = null
        _error.value = null
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
    val reviews by viewModel.reviews.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var year by remember { mutableStateOf(LocalDate.now().year.toString()) }
    var draft by remember { mutableStateOf<Draft?>(null) }

    /** 把预览里勾中的日期并进调休表(同一天的旧安排被覆盖)。 */
    fun applyReviews() {
        val pending = reviews ?: return
        val imported = buildList {
            pending.forEach { review ->
                if (review.restSelected) {
                    review.plan.restDates.forEach { add(ScheduleAdjustment(it, null, review.plan.name)) }
                }
                review.makeups.filter { it.selected && it.source != null }
                    .forEach { add(ScheduleAdjustment(it.date, it.source, review.plan.name)) }
            }
        }
        if (imported.isEmpty()) return
        val importedDates = imported.map { it.date }.toSet()
        viewModel.save((items.filterNot { it.date in importedDates } + imported).sortedBy { it.date })
        viewModel.clearReviews()
    }

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

            // 自动获取:公开节假日接口 → 分组假期 + 补课建议。只生成预览,采用与否自己勾。
            Row(verticalAlignment = Alignment.CenterVertically) {
                YohakuTextField(
                    value = year,
                    onValueChange = { year = it.filter(Char::isDigit).take(4) },
                    label = "年份",
                    modifier = Modifier.width(96.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                YohakuOutlineButton(
                    text = if (loading) "正在获取…" else "获取节假日与调休",
                    enabled = !loading && year.length == 4,
                    onClick = {
                        year.toIntOrNull()?.let {
                            viewModel.fetchHolidays(it, settings.semesterStartDay, settings.semesterWeekCount)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            error?.let {
                Text(
                    text = it,
                    style = YohakuType.label12,
                    color = colors.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            reviews?.let { pending ->
                val picked = pending.sumOf { review ->
                    (if (review.restSelected) review.plan.restDates.size else 0) +
                        review.makeups.count { it.selected }
                }
                Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
                Text(
                    text = "待确认 · $picked 天(补课日期是自动匹配的建议,请核对后采用)",
                    style = YohakuType.label12,
                    color = colors.neutral7,
                )
                pending.forEachIndexed { planIndex, review ->
                    if (review.plan.restDates.isNotEmpty()) {
                        ReviewRow(
                            title = review.plan.name,
                            desc = "${shortDate(review.plan.restDates.first())} – " +
                                "${shortDate(review.plan.restDates.last())} · 停课 ${review.plan.restDates.size} 天",
                            selected = review.restSelected,
                        ) {
                            viewModel.updateReviews { list ->
                                list.mapIndexed { i, r ->
                                    if (i == planIndex) r.copy(restSelected = !r.restSelected) else r
                                }
                            }
                        }
                    }
                    review.makeups.forEachIndexed { makeupIndex, makeup ->
                        ReviewRow(
                            title = "补课 · ${dateLabel(makeup.date.toEpochDay())}",
                            desc = makeup.source
                                ?.let { "补 ${dateLabel(it.toEpochDay())} 的课" }
                                ?: "未匹配到原课程日期",
                            selected = makeup.selected,
                        ) {
                            viewModel.updateReviews { list ->
                                list.mapIndexed { i, r ->
                                    if (i != planIndex) {
                                        r
                                    } else {
                                        r.copy(
                                            makeups = r.makeups.toMutableList().also {
                                                it[makeupIndex] = it[makeupIndex].copy(selected = !makeup.selected)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                YohakuButton(
                    text = "采用所选日期($picked 天)",
                    onClick = { applyReviews() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                YohakuOutlineButton(
                    text = "放弃预览",
                    onClick = { viewModel.clearReviews() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
            }

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

/** "10/1"(区间描述里用,省掉星期) */
private fun shortDate(date: LocalDate): String = "${date.monthValue}/${date.dayOfMonth}"

/** 预览里的一行:描述 + 一个「已选 / 忽略」的开关片。 */
@Composable
private fun ReviewRow(
    title: String,
    desc: String,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = YohakuType.copy13, color = colors.neutral10)
            Text(text = desc, style = YohakuType.label12, color = colors.neutral7)
        }
        YohakuChip(
            text = if (selected) "已选" else "忽略",
            selected = selected,
            onClick = onToggle,
        )
    }
}

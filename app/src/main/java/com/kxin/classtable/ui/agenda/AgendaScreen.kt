package com.kxin.classtable.ui.agenda

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuSegmentedControl
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.rememberEntranceTracker
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AgendaCategory
import com.kxin.classtable.domain.model.AgendaEvent
import com.kxin.classtable.domain.model.AgendaKind
import com.kxin.classtable.domain.model.showsInAgenda
import com.kxin.classtable.domain.model.showsInCountdown
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 日程:两个页签按**归属**分工且互斥 ——
 * **日程**铺日历周条 + 时间线,收待办 / 活动 / 其他(有时间要做的事);**倒计时**按剩余天数铺列表,
 * 收考试 / 作业(等着倒数的目标)。提醒是条目自己的属性,与页签无关。
 * 新建 / 编辑走独立整页(见 [AgendaFormScreen]),默认分类随页签走。
 */
@Composable
fun AgendaScreen(
    nav: NavHostController,
    viewModel: AgendaViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val events by viewModel.events.collectAsStateWithLifecycle()

    val today = LocalDate.now()
    var selectedEpoch by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    var mondayEpoch by rememberSaveable { mutableStateOf(Schedule.mondayEpochDay(today.toEpochDay())) }
    val selected = LocalDate.ofEpochDay(selectedEpoch)
    val monday = LocalDate.ofEpochDay(mondayEpoch)

    // 倒计时的「还有几天」每分钟重算一次
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            nowMillis = System.currentTimeMillis()
        }
    }

    // 新建 / 编辑都跳独立整页(把当前选中日当作新建时的默认日期)
    fun openForm(event: AgendaEvent?, category: AgendaCategory? = null) {
        val date = selected.toEpochDay()
        val base = if (event == null) {
            "agenda_form?date=$date"
        } else {
            "agenda_form?eventId=${event.id}&date=$date"
        }
        nav.navigate(if (category == null) base else "$base&category=${category.name}")
    }

    val agendaEvents = remember(events) { events.filter { it.category.showsInAgenda } }
    val countdownEvents = remember(events) { events.filter { it.category.showsInCountdown } }

    val dayEvents = remember(agendaEvents, selected) {
        agendaEvents.filter { it.spans(selected) }.sortedBy { it.startAt }
    }

    // 页签的位移只有一个来源:可拖动的 pager。点分段 = 把 pager 滚过去,拖动 = 指示块跟着走,
    // 于是「动作」与「视图」永远同源,不再各动各的(过程可控)。
    val entrance = rememberEntranceTracker()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 2 })
    val showCountdown = pagerState.currentPage == 1
    val kind = if (showCountdown) AgendaKind.COUNTDOWN else AgendaKind.SCHEDULE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(
            title = kind.label,
            actions = {
                YohakuSegmentedControl(
                    options = listOf(AgendaKind.SCHEDULE.label, AgendaKind.COUNTDOWN.label),
                    selectedIndex = if (showCountdown) 1 else 0,
                    onSelect = { index ->
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    // 指示块由拖动进度驱动:手指到哪,它到哪
                    progress = pagerState.currentPage + pagerState.currentPageOffsetFraction,
                    modifier = Modifier.width(148.dp),
                )
            },
        )
        // 一行说明:把「这个页签收什么」写在明面上,两个页签的差别不再靠猜
        Text(
            text = when (kind) {
                AgendaKind.SCHEDULE -> "有时间要做的事 · 待办 / 活动 / 其他"
                AgendaKind.COUNTDOWN -> "等着倒数的目标 · 考试 / 作业"
            },
            style = YohakuType.label12,
            color = colors.neutral7,
            modifier = Modifier.padding(
                start = YohakuDimens.screenPadding,
                end = YohakuDimens.screenPadding,
                top = 2.dp,
                bottom = 6.dp,
            ),
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            if (page == 0) {
                Column(modifier = Modifier.fillMaxSize()) {
                    CalendarStrip(
                        monday = monday,
                        selected = selected,
                        today = today,
                        onSelect = { selectedEpoch = it.toEpochDay() },
                        onWeekChange = { newMonday ->
                            mondayEpoch = newMonday.toEpochDay()
                            // 换周后选中日平移到同一星期几,时间线跟着周条走
                            selectedEpoch = newMonday
                                .plusDays((selected.dayOfWeek.value - 1).toLong())
                                .toEpochDay()
                        },
                        onToday = {
                            mondayEpoch = Schedule.mondayEpochDay(today.toEpochDay())
                            selectedEpoch = today.toEpochDay()
                        },
                    )
                    Spacer(
                        modifier = Modifier.height(YohakuDimens.gapTight),
                    )
                    AgendaTimeline(
                        date = selected,
                        today = today,
                        events = dayEvents,
                        onAdd = { openForm(null) },
                        onEventClick = { openForm(it) },
                        entrance = entrance,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                CountdownList(
                    events = countdownEvents,
                    now = nowMillis,
                    onAdd = { openForm(null, AgendaCategory.EXAM) },
                    onEventClick = { openForm(it) },
                    entrance = entrance,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

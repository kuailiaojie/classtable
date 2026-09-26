package com.kxin.classtable.ui.agenda

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AgendaCategory
import com.kxin.classtable.domain.model.AgendaEvent
import com.kxin.classtable.domain.model.showsInAgenda
import com.kxin.classtable.domain.model.showsInCountdown
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * 日程:两个页签按**分类**分工 ——
 * **议程**铺日历周条 + 时间线,收待办 / 活动(要做的事);**倒计时**按剩余天数铺列表,
 * 收考试 / 作业(等着倒数的目标);「其他」两边都出现。提醒是条目自己的属性,与页签无关。
 * 新建 / 编辑走独立整页(见 [AgendaFormScreen])。
 */
@Composable
fun AgendaScreen(
    nav: NavHostController,
    viewModel: AgendaViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val events by viewModel.events.collectAsStateWithLifecycle()

    val today = LocalDate.now()
    var showCountdown by rememberSaveable { mutableStateOf(false) }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(
            title = "日程",
            actions = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    YohakuChip(
                        text = "议程",
                        selected = !showCountdown,
                        onClick = { showCountdown = false },
                    )
                    YohakuChip(
                        text = "倒计时",
                        selected = showCountdown,
                        onClick = { showCountdown = true },
                    )
                }
            },
        )

        if (!showCountdown) {
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
            Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
            AgendaTimeline(
                date = selected,
                today = today,
                events = dayEvents,
                onAdd = { openForm(null) },
                onEventClick = { openForm(it) },
                modifier = Modifier.weight(1f),
            )
        } else {
            CountdownList(
                events = countdownEvents,
                now = nowMillis,
                onAdd = { openForm(null, AgendaCategory.EXAM) },
                onEventClick = { openForm(it) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

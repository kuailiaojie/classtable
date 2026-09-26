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
import com.kxin.classtable.domain.model.AgendaEvent
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * 日程:一份数据两种看法 ——
 * **议程**按天铺日历周条 + 时间线,**倒计时**按剩余天数铺列表;右上角切换。
 * 新建 / 编辑走底部面板(见 [AgendaSheet])。
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

    var sheetOpen by remember { mutableStateOf(false) }
    var sheetInitial by remember { mutableStateOf<AgendaEvent?>(null) }
    fun openSheet(event: AgendaEvent?) {
        sheetInitial = event
        sheetOpen = true
    }

    val dayEvents = remember(events, selected) {
        events.filter { it.spans(selected) }.sortedBy { it.startAt }
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
                onAdd = { openSheet(null) },
                onEventClick = { openSheet(it) },
                modifier = Modifier.weight(1f),
            )
        } else {
            CountdownList(
                events = events,
                now = nowMillis,
                onAdd = { openSheet(null) },
                onEventClick = { openSheet(it) },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (sheetOpen) {
        AgendaSheet(
            initial = sheetInitial,
            defaultDate = selected,
            onDismiss = { sheetOpen = false },
            onSave = {
                viewModel.save(it)
                sheetOpen = false
            },
            onDelete = { id ->
                viewModel.delete(id)
                sheetOpen = false
            },
        )
    }
}

package com.kxin.classtable.ui.day

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuCard
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.courseTint
import com.kxin.classtable.domain.Adjustments
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.Course
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class DayViewModel @Inject constructor(
    courseRepository: CourseRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    /**
     * 订阅全部课程而不是「今天星期几」那一批:调休可能让今天去上**别的星期几**的课
     * (周六补周四的课),按星期订阅就取不到了。
     */
    val courses: StateFlow<List<Course>> = courseRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
}

/**
 * 日视图:纯列表,不画时间轴刻度。卡片间 32px 留白 = 空堂。
 * 当前课程 = accent 左边条。
 */
@Composable
fun DayScreen(
    nav: NavHostController,
    viewModel: DayViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val periods = remember(settings.periodTimes) { Schedule.parsePeriods(settings.periodTimes) }
    val adjustments = remember(settings.scheduleAdjustments) {
        Adjustments.decode(settings.scheduleAdjustments)
    }
    val today = LocalDate.now()
    // 今天怎么过:停课 / 补课(按原课程日期那天上) / 正常
    val todayPlan = remember(adjustments, today) { Adjustments.resolve(adjustments, today) }
    val teachingDay = remember(adjustments, settings.semesterStartDay, settings.semesterWeekCount, today) {
        Adjustments.teachingDay(adjustments, today, settings.semesterStartDay, settings.semesterWeekCount)
    }
    val todayCourses = remember(courses, teachingDay, periods) {
        if (teachingDay == null) {
            emptyList()
        } else {
            courses
                .filter { it.isOnWeekday(teachingDay.second) && it.isActiveOnWeek(teachingDay.first) }
                .sortedBy { Schedule.courseStartMinute(it, periods) ?: Int.MAX_VALUE }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(title = "今天")

        val date = LocalDate.now()
        val weekName = "一二三四五六日"[date.dayOfWeek.value - 1]
        // 实时动态:每 30 秒刷新一次「正在上课 / 距下一节还有多久」
        var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(30_000)
                nowMillis = System.currentTimeMillis()
            }
        }
        val nowMinute = Schedule.minuteOfDay(nowMillis)
        val scheduled = todayCourses.mapNotNull { course ->
            val s = Schedule.courseStartMinute(course, periods) ?: return@mapNotNull null
            val e = Schedule.courseEndMinute(course, periods) ?: return@mapNotNull null
            Triple(course, s, e)
        }.sortedBy { it.second }
        val ongoing = scheduled.firstOrNull { nowMinute >= it.second && nowMinute < it.third }
        val next = scheduled.firstOrNull { it.second > nowMinute }

        Column(modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding)) {
            Text(
                text = "${date.monthValue}月${date.dayOfMonth}日 周$weekName",
                style = YohakuType.title20,
                color = colors.neutral10,
            )
            if (todayPlan.rest || todayPlan.makeup) {
                Text(
                    text = if (todayPlan.rest) {
                        "调休 · 今天停课"
                    } else {
                        "调休 · 今天补 ${todayPlan.effectiveDate.monthValue}/${todayPlan.effectiveDate.dayOfMonth} 的课"
                    },
                    style = YohakuType.label12,
                    color = colors.accent,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            when {
                ongoing != null -> Text(
                    text = "正在上课:${ongoing.first.name} · 还有 ${ongoing.third - nowMinute} 分钟下课",
                    style = YohakuType.label12,
                    color = colors.accent,
                    modifier = Modifier.padding(top = 4.dp),
                )
                next != null -> Text(
                    text = "距 ${next.first.name} 上课还有 ${next.second - nowMinute} 分钟",
                    style = YohakuType.label12,
                    color = colors.neutral7,
                    modifier = Modifier.padding(top = 4.dp),
                )
                else -> Text(
                    text = "今天没有更多课了",
                    style = YohakuType.label12,
                    color = colors.neutral7,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
        }

        Box(modifier = Modifier.weight(1f)) {
            if (todayCourses.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (todayPlan.rest) "今天停课（调休）" else "今天没有课",
                        style = YohakuType.copy14,
                        color = colors.neutral7,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = YohakuDimens.screenPadding,
                        end = YohakuDimens.screenPadding,
                        top = YohakuDimens.gapCard,
                        // 末尾预留悬浮导航的高度:中途内容从栏下穿过,最后一节课仍能卷上来
                        bottom = YohakuDimens.gapCard + YohakuDimens.navReservedHeight,
                    ),
                    verticalArrangement = Arrangement.spacedBy(YohakuDimens.gapSection),
                ) {
                    items(todayCourses, key = { it.id }) { course ->
                        // 直接按课程自己的时间区间判断「正在上」。
                        // 以前是按 bigPeriods 两行一组去配对(2 小节 = 1 大节),作息本身是大节时
                        // (如长江大学 8 个 95 分钟的节)会配错:第 2 节上课时把第 1 节的课标成正在上。
                        val isCurrent = Schedule.isCourseOngoing(course, periods)
                        YohakuCard(accentBar = isCurrent, containerColor = courseTint(course)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    text = Schedule.courseTimeText(course, periods),
                                    style = YohakuType.timeMono,
                                    color = colors.neutral7,
                                    modifier = Modifier.width(100.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = course.name,
                                        style = YohakuType.copy16,
                                        color = colors.neutral10,
                                    )
                                    val meta = listOf(course.location, course.teacher)
                                        .filter { it.isNotEmpty() }
                                        .joinToString(" · ")
                                    if (meta.isNotEmpty()) {
                                        Text(
                                            text = meta,
                                            style = YohakuType.copy13,
                                            color = colors.neutral7,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

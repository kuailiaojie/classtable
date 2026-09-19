package com.kxin.classtable.ui.week

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuBottomNav
import com.kxin.classtable.design.YohakuCard
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.WeekType
import com.kxin.classtable.ui.navigateToTab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WeekViewModel @Inject constructor(
    private val courseRepository: CourseRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val courses: StateFlow<List<Course>> = courseRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
}

/**
 * 周视图:一屏一天,左右滑动切换周一到周日(不把七天挤在同一面)。
 * 每天 = 逐节网格:节行固定等高整整齐齐,左侧标「第 N 节 + 起止时间」;
 * 课程卡绝对定位横穿:普通课横穿其节次区间,自定义时间课程横穿其起止时间覆盖的所有节行。
 * 格子显示课程名 + 时间 + 地点,点按弹出完整信息。
 */
@Composable
fun WeekScreen(
    nav: NavHostController,
    viewModel: WeekViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val weekCount = settings.semesterWeekCount.coerceAtLeast(1)
    // 周次实时推算(以开学日所在周的周一为锚),不再依赖持久化旧值;
    // ‹/› 只做临时偏移浏览,「今天」归零。跨天/回前台会自动重算。
    val realWeek = Schedule.currentWeek(settings.semesterStartDay, weekCount)
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    val week = (realWeek + weekOffset).coerceIn(1, weekCount)
    val today = Schedule.todayWeekday()
    val periods = remember(settings.periodTimes) { Schedule.parsePeriods(settings.periodTimes) }
    val weekRange = Schedule.weekRangeText(settings.semesterStartDay, week)
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = today - 1) { 7 }
    val day = pagerState.currentPage + 1
    var detailCourse by remember { mutableStateOf<Course?>(null) }

    detailCourse?.let { course ->
        AlertDialog(
            onDismissRequest = { detailCourse = null },
            title = { Text(course.name, style = YohakuType.title20) },
            text = {
                Column {
                    InfoLine(
                        label = "时间",
                        value = "${Course.weekdaysText(course)} · " +
                            Schedule.courseTimeText(course, periods),
                    )
                    if (course.location.isNotEmpty()) InfoLine(label = "地点", value = course.location)
                    if (course.teacher.isNotEmpty()) InfoLine(label = "教师", value = course.teacher)
                    InfoLine(label = "周次", value = weekSummary(course))
                }
            },
            confirmButton = {
                Text(
                    text = "查看详情",
                    style = YohakuType.copy14,
                    color = colors.accent,
                    modifier = Modifier
                        .clickable {
                            detailCourse = null
                            nav.navigate("course_detail/${course.id}")
                        }
                        .padding(8.dp),
                )
            },
            dismissButton = {
                Text(
                    text = "关闭",
                    style = YohakuType.copy14,
                    color = colors.neutral7,
                    modifier = Modifier
                        .clickable { detailCourse = null }
                        .padding(8.dp),
                )
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // 顶部:今天日期星期 + 周信息 + 周导航 + 添加
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = YohakuDimens.screenPadding, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = Schedule.todayDateText(),
                        style = YohakuType.title20,
                        color = colors.neutral10,
                    )
                    Text(
                        text = "第 $week 周" +
                            (if (weekRange.isNotEmpty()) " · $weekRange" else "") +
                            (if (settings.semesterStartDay <= 0L) " · 未设置开学日" else ""),
                        style = YohakuType.copy13,
                        color = colors.neutral7,
                    )
                }
                Text(
                    text = "‹",
                    style = YohakuType.title24,
                    color = if (week > 1) colors.neutral9 else colors.neutral5,
                    modifier = Modifier
                        .clickable(enabled = week > 1) { weekOffset -= 1 }
                        .padding(8.dp),
                )
                Text(
                    text = "›",
                    style = YohakuType.title24,
                    color = if (week < weekCount) colors.neutral9 else colors.neutral5,
                    modifier = Modifier
                        .clickable(enabled = week < weekCount) { weekOffset += 1 }
                        .padding(8.dp),
                )
                Text(
                    text = "今天",
                    style = YohakuType.copy13,
                    color = colors.neutral7,
                    modifier = Modifier
                        .clickable {
                            weekOffset = 0
                            scope.launch { pagerState.scrollToPage(today - 1) }
                        }
                        .padding(start = 8.dp),
                )
                Text(
                    text = "添加",
                    style = YohakuType.copy13,
                    color = colors.accent,
                    modifier = Modifier
                        .clickable { nav.navigate("course_form") }
                        .padding(start = 16.dp),
                )
            }

            // 星期切换胶囊(左右滑动可切换,点击直达)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = YohakuDimens.screenPadding, vertical = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                val weekNames = listOf("一", "二", "三", "四", "五", "六", "日")
                weekNames.forEachIndexed { index, name ->
                    val selected = day == index + 1
                    Text(
                        text = name,
                        style = YohakuType.label12,
                        color = if (selected) androidx.compose.ui.graphics.Color.White else colors.neutral7,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (selected) colors.accent else colors.neutral2,
                                androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                            )
                            .clickable { scope.launch { pagerState.scrollToPage(index) } }
                            .padding(vertical = 6.dp),
                    )
                }
            }

            // 单日分页:左右滑动看周一~周日
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                val d = page + 1
                val dayCourses = courses.filter { it.isOnWeekday(d) && it.isActiveOnWeek(week) }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    // 日期标题
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = YohakuDimens.screenPadding, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "周${"一二三四五六日"[d - 1]}",
                            style = YohakuType.title20,
                            color = colors.neutral10,
                        )
                        Text(
                            text = "  ${dayCourses.size} 门课 · 左滑看其他日期",
                            style = YohakuType.label12,
                            color = colors.neutral7,
                        )
                    }

                    // 网格:底层/中层是横穿的课程卡(绝对定位),顶层是整整齐齐的节行。
                    // 普通课横穿其节次区间;自定义时间课程横穿其起止时间覆盖的所有节行。
                    val rowH = YohakuDimens.gridRowHeight
                    Box(modifier = Modifier.fillMaxWidth()) {
                        // 底层:自定义时间课程(按起止分钟精确横穿,未对齐节次也贴合)
                        dayCourses.filter { it.hasCustomTime() }.forEach { c ->
                            val (top, height) = Schedule.customCourseLayout(c, periods)
                            if (height > 0f) {
                                CourseOverlayCard(
                                    course = c,
                                    top = top,
                                    height = height,
                                    rowH = rowH,
                                    isCurrent = d == today && Schedule.isCourseOngoing(c, periods),
                                    periods = periods,
                                    onClick = { detailCourse = it },
                                )
                            }
                        }
                        // 中层:按节次课程(横穿其节次区间)
                        dayCourses.filter { !it.hasCustomTime() }.forEach { c ->
                            CourseOverlayCard(
                                course = c,
                                top = (c.startPeriod - 1).toFloat(),
                                height = (c.endPeriod - c.startPeriod + 1).toFloat(),
                                rowH = rowH,
                                isCurrent = d == today && Schedule.isCourseOngoing(c, periods),
                                periods = periods,
                                onClick = { detailCourse = it },
                            )
                        }
                        // 顶层:整整齐齐的节行(左侧「第 N 节 + 起止时间」)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            periods.forEachIndexed { idx, period ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = YohakuDimens.screenPadding),
                                ) {
                                    Column(modifier = Modifier.width(YohakuDimens.gridPeriodLabelWidth)) {
                                        Text(
                                            text = "第${idx + 1}节",
                                            style = YohakuType.label12,
                                            color = colors.neutral6,
                                            modifier = Modifier.padding(top = 8.dp),
                                        )
                                        Text(
                                            text = Schedule.timeRangeText(period.start, period.end),
                                            style = YohakuType.cellTime,
                                            color = colors.neutral7,
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(2.dp)
                                            .height(rowH),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
                }
            }
        }

        YohakuBottomNav(current = "week", onNavigate = { nav.navigateToTab(it) })
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val colors = LocalYohakuColors.current
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = YohakuType.label12,
            color = colors.neutral7,
            modifier = Modifier.width(44.dp),
        )
        Text(text = value, style = YohakuType.copy14, color = colors.neutral9)
    }
}

/** 横穿多节的课程卡:绝对定位在网格上,top/height 为行高倍数(自定义课按分钟比例精确)。 */
@Composable
private fun CourseOverlayCard(
    course: Course,
    top: Float,
    height: Float,
    rowH: Dp,
    isCurrent: Boolean,
    periods: List<Schedule.Period>,
    onClick: (Course) -> Unit,
) {
    val colors = LocalYohakuColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = YohakuDimens.screenPadding + YohakuDimens.gridPeriodLabelWidth + 2.dp,
                end = YohakuDimens.screenPadding + 2.dp,
            )
            .offset(y = rowH * top)
            .height(rowH * height)
            .clickable { onClick(course) },
    ) {
        YohakuCard(modifier = Modifier.fillMaxSize(), accentBar = isCurrent) {
            Text(
                text = course.name,
                style = YohakuType.cellName,
                color = colors.neutral10,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val time = Schedule.courseTimeText(course, periods)
            Text(
                text = if (course.location.isNotEmpty()) "$time ${course.location}" else time,
                style = YohakuType.cellTime,
                color = colors.neutral7,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun weekSummary(course: Course): String = when (course.weekType) {
    WeekType.EVERY_WEEK -> "每周"
    WeekType.ODD_WEEK -> "单周"
    WeekType.EVEN_WEEK -> "双周"
    WeekType.CUSTOM -> "第${course.weekStart}-${course.weekEnd}周"
}

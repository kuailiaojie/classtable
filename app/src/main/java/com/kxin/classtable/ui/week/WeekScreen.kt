package com.kxin.classtable.ui.week

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
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
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.courseTint
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.WeekType
import com.kxin.classtable.ui.navigateToTab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
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
 * 周视图:一屏看全一周七天,不再左右翻页。
 *
 * 横向 = 周一…周日七列,纵向 = 按节次等高的行;行高由可用高度除以节数算出(不低于下限,
 * 放不下时整格纵向滚动),所以「七天 + 全部节次」始终同屏。
 * 课程块绝对定位横穿其节次区间(自定义时间课程按分钟比例精确定位);同一时段有多门课时
 * 按车道并排。左侧只留 28dp 放节号与起始时间,把宽度还给列。
 * 填充色是每门课的淡彩(便于扫读),accent 仍然只表示「此刻正在上」。
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
    val dayNumbers = remember(settings.semesterStartDay, week) {
        Schedule.weekDayNumbers(settings.semesterStartDay, week)
    }
    var detailCourse by remember { mutableStateOf<Course?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    // 当前时间线每分钟重算一次(只在浏览真实当前周时画)
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            nowMillis = System.currentTimeMillis()
        }
    }
    val nowMinute = ((nowMillis / 60_000).toInt() % 1440)
    val showNowLine = weekOffset == 0

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

    if (showAdd) {
        AddDialog(
            onDismiss = { showAdd = false },
            onPick = { route ->
                showAdd = false
                nav.navigate(route)
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        // 顶部:今天日期星期 + 周信息 + 周导航 + 添加
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = YohakuDimens.screenPadding, vertical = 8.dp),
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
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Text(
                text = "›",
                style = YohakuType.title24,
                color = if (week < weekCount) colors.neutral9 else colors.neutral5,
                modifier = Modifier
                    .clickable(enabled = week < weekCount) { weekOffset += 1 }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Text(
                text = "今天",
                style = YohakuType.copy13,
                color = colors.neutral7,
                modifier = Modifier
                    .clickable { weekOffset = 0 }
                    .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            )
            Text(
                text = "添加",
                style = YohakuType.copy13,
                color = colors.accent,
                modifier = Modifier
                    .clickable { showAdd = true }
                    .padding(vertical = 4.dp),
            )
        }

        // 表头:星期 + 日号(与网格列严格对齐)
        WeekdayHeader(dayNumbers = dayNumbers, today = today)

        // 网格:七列 × 全部节次,一屏看完
        WeekGrid(
            courses = courses,
            periods = periods,
            week = week,
            today = today,
            nowMinute = nowMinute,
            showNowLine = showNowLine,
            onCourseClick = { detailCourse = it },
            modifier = Modifier.weight(1f),
        )

        YohakuBottomNav(current = "week", onNavigate = { nav.navigateToTab(it) })
    }
}

/** 表头:一~日 + 该天日号;今天用 accent 标出。 */
@Composable
private fun WeekdayHeader(dayNumbers: List<Int>, today: Int) {
    val colors = LocalYohakuColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = YohakuDimens.gridPadding, vertical = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Spacer(modifier = Modifier.width(YohakuDimens.gridGutterWidth))
        (1..7).forEach { d ->
            val isToday = d == today
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "一二三四五六日"[d - 1].toString(),
                    style = YohakuType.gridWeekday,
                    color = if (isToday) colors.accent else colors.neutral8,
                )
                Text(
                    text = dayNumbers.getOrNull(d - 1)?.toString() ?: "",
                    style = YohakuType.gridDate,
                    color = if (isToday) colors.accent else colors.neutral6,
                )
                Box(
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .width(12.dp)
                        .height(2.dp)
                        .background(if (isToday) colors.accent else Color.Transparent),
                )
            }
        }
    }
}

/** 网格:左侧节次留白列 + 七列,课程块绝对定位。 */
@Composable
private fun WeekGrid(
    courses: List<Course>,
    periods: List<Schedule.Period>,
    week: Int,
    today: Int,
    nowMinute: Int,
    showNowLine: Boolean,
    onCourseClick: (Course) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    val rowCount = periods.size.coerceAtLeast(1)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 行高:优先铺满可用高度;低于下限(小屏或节次特别多)时整格纵向滚动
        val rowH = maxOf(maxHeight / rowCount, YohakuDimens.gridMinRowHeight)
        val gridHeight = rowH * rowCount
        val colW = (maxWidth - YohakuDimens.gridPadding * 2 - YohakuDimens.gridGutterWidth) / 7
        val gutter = YohakuDimens.gridGutterWidth
        val lanesPerDay = (1..7).map { d ->
            val dayCourses = courses.filter { it.isOnWeekday(d) && it.isActiveOnWeek(week) }
            layoutDay(dayCourses, periods)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(gridHeight),
            ) {
                // 今天整列淡淡的底色(只做方位提示,不喧哗)
                if (today in 1..7) {
                    Box(
                        modifier = Modifier
                            .offset(x = YohakuDimens.gridPadding + gutter + colW * (today - 1))
                            .width(colW)
                            .fillMaxHeight()
                            .background(colors.neutral1),
                    )
                }

                // 节次留白列:节号 + 每个大节首行的开始时间
                Column(
                    modifier = Modifier
                        .width(gutter)
                        .fillMaxHeight()
                        .padding(end = 3.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    periods.forEachIndexed { idx, period ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(rowH),
                            horizontalAlignment = Alignment.End,
                        ) {
                            Text(
                                text = "${idx + 1}",
                                style = YohakuType.gridGutter,
                                color = colors.neutral6,
                            )
                            if (idx % 2 == 0) {
                                Text(
                                    text = Schedule.clockText(period.start),
                                    style = YohakuType.gridGutterTime,
                                    color = colors.neutral5,
                                )
                            }
                        }
                    }
                }

                // 课程块(按天分列,同一天内按车道并排)
                lanesPerDay.forEachIndexed { dayIdx, (blocks, laneCount) ->
                    val used = laneCount.coerceAtMost(3)
                    val laneW = colW / used
                    blocks.forEach { block ->
                        val lane = block.lane.coerceAtMost(used - 1)
                        CourseBlock(
                            course = block.course,
                            rowH = rowH,
                            top = block.top,
                            height = block.height,
                            x = YohakuDimens.gridPadding + gutter + colW * dayIdx + laneW * lane,
                            width = laneW,
                            isCurrent = dayIdx + 1 == today &&
                                Schedule.isCourseOngoing(block.course, periods),
                            onClick = onCourseClick,
                        )
                    }
                }

                // 当前时间线:只画在「今天」那一列
                if (showNowLine && today in 1..7) {
                    Schedule.fractionalRow(nowMinute, periods)?.let { row ->
                        Box(
                            modifier = Modifier
                                .offset(
                                    x = YohakuDimens.gridPadding + gutter + colW * (today - 1),
                                    y = rowH * row,
                                )
                                .width(colW)
                                .height(1.5.dp)
                                .background(colors.accent),
                        )
                    }
                }
            }
        }
    }
}

/** 网格中的一个课程块:淡彩底,当前正在上的课带 accent 左边条。 */
@Composable
private fun CourseBlock(
    course: Course,
    rowH: Dp,
    top: Float,
    height: Float,
    x: Dp,
    width: Dp,
    isCurrent: Boolean,
    onClick: (Course) -> Unit,
) {
    val colors = LocalYohakuColors.current
    val blockH = rowH * height
    // 够高才放第二行(教室);只够一行时让课程名独占
    val roomy = blockH >= 30.dp
    Box(
        modifier = Modifier
            .offset(x = x, y = rowH * top)
            .width(width)
            .height((blockH - YohakuDimens.gridCellGap).coerceAtLeast(12.dp))
            .clip(RoundedCornerShape(YohakuDimens.radiusControl))
            .background(courseTint(course))
            .clickable { onClick(course) },
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .width(YohakuDimens.gridAccentBarWidth)
                        .fillMaxHeight()
                        .background(colors.accent),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = YohakuDimens.gridBlockPadding, vertical = 2.dp),
            ) {
                Text(
                    text = course.name,
                    style = YohakuType.gridName,
                    color = colors.neutral10,
                    maxLines = if (roomy) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (roomy && course.location.isNotEmpty()) {
                    Text(
                        text = course.location,
                        style = YohakuType.gridMeta,
                        color = colors.neutral7,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 一天内的课程块:换算成 (top, height) 行高倍数后,贪心分配并排车道。 */
private data class GridBlock(
    val course: Course,
    val top: Float,
    val height: Float,
    val lane: Int = 0,
)

private fun layoutDay(
    courses: List<Course>,
    periods: List<Schedule.Period>,
): Pair<List<GridBlock>, Int> {
    val blocks = courses.mapNotNull { c ->
        val (top, height) = if (c.hasCustomTime()) {
            Schedule.customCourseLayout(c, periods)
        } else {
            (c.startPeriod - 1).toFloat() to (c.endPeriod - c.startPeriod + 1).toFloat()
        }
        if (height <= 0f) null else GridBlock(c, top, height)
    }.sortedWith(compareBy({ it.top }, { -it.height }))

    // 车道:每条车道记住已占据到的最底部,能塞就复用
    val laneEnds = mutableListOf<Float>()
    val placed = blocks.map { b ->
        val free = laneEnds.indexOfFirst { it <= b.top + 0.01f }
        val lane = if (free >= 0) free else laneEnds.size.also { laneEnds.add(0f) }
        laneEnds[lane] = b.top + b.height
        b.copy(lane = lane)
    }
    return placed to laneEnds.size.coerceAtLeast(1)
}

/** 「添加」弹层:教务导入 / 手动添加 / 手动表格导入 / AI 图片导入。 */
@Composable
private fun AddDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val colors = LocalYohakuColors.current
    val options = listOf(
        Triple("教务导入", "3 步导入,选学校适配器", "import"),
        Triple("手动添加", "自己填一门课", "course_form"),
        Triple("手动表格导入", "粘贴表格或选 Excel 文件", "import_manual"),
        Triple("AI 图片导入", "截图自动识别课表", "import_ai"),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加", style = YohakuType.title20) },
        text = {
            Column {
                options.forEachIndexed { index, (title, desc, route) ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(colors.neutral3),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(route) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = title, style = YohakuType.copy15, color = colors.neutral10)
                            Text(text = desc, style = YohakuType.label12, color = colors.neutral7)
                        }
                        Text(text = "›", style = YohakuType.copy15, color = colors.neutral6)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Text(
                text = "取消",
                style = YohakuType.copy14,
                color = colors.neutral7,
                modifier = Modifier
                    .clickable { onDismiss() }
                    .padding(8.dp),
            )
        },
    )
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

private fun weekSummary(course: Course): String = when (course.weekType) {
    WeekType.EVERY_WEEK -> "每周"
    WeekType.ODD_WEEK -> "单周"
    WeekType.EVEN_WEEK -> "双周"
    WeekType.CUSTOM -> "第${course.weekStart}-${course.weekEnd}周"
}

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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.kxin.classtable.design.YohakuDialog
import com.kxin.classtable.design.YohakuDialogAction
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.courseTint
import com.kxin.classtable.domain.Adjustments
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.ScheduleAdjustment
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.WeekType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
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
 * 周视图:一屏看全一周七天,左右滑动翻周。
 *
 * 每一页 = 一周:横向七列(周一~周日,表头带日号、今天高亮),纵向按节次等高等分,
 * 所以「七天 + 全部节次」始终同屏。翻周直接横滑(不再有 ‹ › 按钮),「今天」跳回真实当前周。
 * 行高由可用高度除以节数算出(不低于下限,放不下时整格纵向滚动)。
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
    // 周次实时推算(以开学日所在周的周一为锚),不再依赖持久化旧值;跨天/回前台会自动重算。
    val realWeek = Schedule.currentWeek(settings.semesterStartDay, weekCount)
    val periods = remember(settings.periodTimes) { Schedule.parsePeriods(settings.periodTimes) }
    val adjustments = remember(settings.scheduleAdjustments) {
        Adjustments.decode(settings.scheduleAdjustments)
    }
    val today = Schedule.todayWeekday()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = (realWeek - 1).coerceIn(0, weekCount - 1)) {
        weekCount
    }
    LaunchedEffect(realWeek, weekCount) {
        val currentPage = (realWeek - 1).coerceIn(0, weekCount - 1)
        if (pagerState.currentPage != currentPage) {
            pagerState.animateScrollToPage(currentPage)
        }
    }
    val week = pagerState.currentPage + 1
    val weekRange = Schedule.weekRangeText(settings.semesterStartDay, week)
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
    val nowMinute = Schedule.minuteOfDay(nowMillis)

    detailCourse?.let { course ->
        YohakuDialog(
            onDismissRequest = { detailCourse = null },
            title = course.name,
            actions = {
                YohakuDialogAction(text = "关闭", onClick = { detailCourse = null })
                YohakuDialogAction(
                    text = "查看详情",
                    accent = true,
                    onClick = {
                        detailCourse = null
                        nav.navigate("course_detail/${course.id}")
                    },
                )
            },
        ) {
            InfoLine(
                label = "时间",
                value = "${Course.weekdaysText(course)} · " +
                    Schedule.courseTimeText(course, periods),
            )
            if (course.location.isNotEmpty()) InfoLine(label = "地点", value = course.location)
            if (course.teacher.isNotEmpty()) InfoLine(label = "教师", value = course.teacher)
            InfoLine(label = "周次", value = weekSummary(course))
        }
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
        // 顶部:今天日期星期 + 周信息 + 今天(翻周改用左右滑动)
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
                text = "今天",
                style = YohakuType.copy13,
                color = colors.neutral7,
                modifier = Modifier
                    .clickable { scope.launch { pagerState.animateScrollToPage(realWeek - 1) } }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
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

        // 一周一页:左右滑动翻周
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            beyondViewportPageCount = 1,
        ) { page ->
            val pageWeek = page + 1
            val dayNumbers = remember(settings.semesterStartDay, pageWeek) {
                Schedule.weekDayNumbers(settings.semesterStartDay, pageWeek)
            }
            val teachingDays = remember(
                adjustments,
                settings.semesterStartDay,
                settings.semesterWeekCount,
                pageWeek,
            ) {
                resolveTeachingDays(
                    adjustments,
                    settings.semesterStartDay,
                    settings.semesterWeekCount,
                    pageWeek,
                )
            }
            // 「休」= 这天停课;「补」= 这天上的不是本来的星期(补别的日子的课)
            val marks = teachingDays.mapIndexed { index, teaching ->
                when {
                    teaching == null -> "休"
                    teaching.second != index + 1 -> "补"
                    else -> null
                }
            }
            Column(modifier = Modifier.fillMaxSize()) {
                WeekdayHeader(dayNumbers = dayNumbers, today = today, marks = marks)
                WeekGrid(
                    courses = courses,
                    periods = periods,
                    week = pageWeek,
                    today = today,
                    nowMinute = nowMinute,
                    showNowLine = pageWeek == realWeek,
                    teachingDays = teachingDays,
                    onCourseClick = { detailCourse = it },
                    // 网格要**排在悬浮导航之上**:以前让它铺到屏幕底部再从栏下穿过,
                    // 结果是最后一两节被导航栏永久压住(还要靠滚动才能看见),对课表来说是错的。
                    modifier = Modifier
                        .weight(1f)
                        .padding(bottom = YohakuDimens.navReservedHeight),
                )
            }
        }
    }
}

/** 表头:一~日 + 该天日号;今天用 accent 标出,调休日标「休 / 补」。 */
@Composable
private fun WeekdayHeader(dayNumbers: List<Int>, today: Int, marks: List<String?>) {
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
            val mark = marks.getOrNull(d - 1)
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
                if (mark != null) {
                    Text(
                        text = mark,
                        style = YohakuType.gridMeta,
                        color = colors.accent,
                    )
                } else {
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
}

/**
 * 这一周七天各按哪一天上课:返回 `(周次, 星期几)`,停课为 null。
 *
 * 没设开学日时拿不到具体日期,调休无从谈起,退化成「自然星期」。
 */
private fun resolveTeachingDays(
    adjustments: List<ScheduleAdjustment>,
    semesterStartDay: Long,
    weekCount: Int,
    week: Int,
): List<Pair<Int, Int>?> {
    val dates = Schedule.weekDates(semesterStartDay, week)
    if (dates.isEmpty()) return (1..7).map { week to it }
    return dates.map { Adjustments.teachingDay(adjustments, it, semesterStartDay, weekCount) }
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
    teachingDays: List<Pair<Int, Int>?>,
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
        // 列取课按「这一天实际上哪天的课」算:停课的列空着,补课的列去取原课程日期的课
        val lanesPerDay = (1..7).map { d ->
            val teaching = teachingDays.getOrNull(d - 1)
            val dayCourses = if (teaching == null) {
                emptyList()
            } else {
                courses.filter { it.isOnWeekday(teaching.second) && it.isActiveOnWeek(teaching.first) }
            }
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

                // 每个节次的横向刻度直接落在该行顶部,与左侧时间文字和课程块共用 rowH。
                periods.forEachIndexed { idx, _ ->
                    Box(
                        modifier = Modifier
                            .offset(x = YohakuDimens.gridPadding, y = rowH * idx)
                            .width(maxWidth - YohakuDimens.gridPadding * 2)
                            .height(1.dp)
                            .background(colors.neutral3),
                    )
                }

                // 节次留白列:每行「节号 + 该节开始时间」。
                //
                // 以前这里只给**奇数行**打时间(if (idx % 2 == 0)),那是按「2 小节 = 1 大节」
                // 的默认作息表来的;但作息现在可以是任意的(长江大学本身就是 8 个 95 分钟的大节,
                // 不是 12 个小节),于是第 2/4/6/8 节整行没有时间,第 2 节的课(10:05)紧挨着的
                // 标签是第 1 节的 08:00 —— 看起来就成了「课程时间对、跟左边节次对不上」。
                // 每一节都标出开始时间,对任何作息表都成立。
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
                                // 显示**节次号**(数据里的),不是行号:教务第 8 节可能排在第 5 行上
                                text = "${period.number}",
                                style = YohakuType.gridGutter,
                                color = colors.neutral8,
                            )
                            Text(
                                text = Schedule.clockText(period.start),
                                // 这一列是「现在是第几节、几点」的唯一参照,必须看得清:
                                // 之前用 neutral5(最淡那一档)+8sp,基本读不出来,等于没有时间轴
                                style = YohakuType.gridGutterTime,
                                color = colors.neutral7,
                            )
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

                // 当前时间线:只画在「今天」那一列。
                // 必须带标签 —— 光一条横线没人知道那是什么,之前就被当成「莫名多出来的小横条」。
                if (showNowLine && today in 1..7) {
                    Schedule.fractionalRow(nowMinute, periods)?.let { row ->
                        val lineX = YohakuDimens.gridPadding + gutter + colW * (today - 1)
                        val lineY = rowH * row
                        Box(
                            modifier = Modifier
                                .offset(x = lineX, y = lineY)
                                .width(colW)
                                .height(1.5.dp)
                                .background(colors.accent),
                        )
                        Box(
                            modifier = Modifier
                                .offset(x = lineX, y = (lineY - 12.dp).coerceAtLeast(0.dp))
                                .background(colors.paper)
                                .padding(horizontal = 3.dp),
                        ) {
                            Text(
                                text = "现在",
                                style = YohakuType.gridMeta,
                                color = colors.accent,
                            )
                        }
                    }
                }
            }
            // 网格已经排在悬浮导航之上(见调用处的 padding),这里不需要再留栏体高度:
            // 七行正好铺满可见区域,「一屏看全一周」不再需要滚动。
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
            // 节次号 → 行下标(两者已解耦:教务第 8 节可能落在网格的第 5 行)
            val firstRow = Schedule.rowOf(periods, c.startPeriod)
            val lastRow = Schedule.rowOf(periods, c.endPeriod)
            if (firstRow < 0 || lastRow < firstRow) return@mapNotNull null
            firstRow.toFloat() to (lastRow - firstRow + 1).toFloat()
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
    YohakuDialog(
        onDismissRequest = onDismiss,
        title = "添加",
        actions = { YohakuDialogAction(text = "取消", onClick = onDismiss) },
    ) {
        options.forEachIndexed { index, (label, desc, route) ->
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
                    Text(text = label, style = YohakuType.copy15, color = colors.neutral10)
                    Text(text = desc, style = YohakuType.label12, color = colors.neutral7)
                }
                Text(text = "›", style = YohakuType.copy15, color = colors.neutral6)
            }
        }
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

private fun weekSummary(course: Course): String = when (course.weekType) {
    WeekType.EVERY_WEEK -> "每周"
    WeekType.ODD_WEEK -> "单周"
    WeekType.EVEN_WEEK -> "双周"
    WeekType.CUSTOM -> "第${course.weekStart}-${course.weekEnd}周"
}

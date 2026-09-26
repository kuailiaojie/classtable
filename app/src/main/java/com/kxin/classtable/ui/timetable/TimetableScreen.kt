package com.kxin.classtable.ui.timetable

import android.content.Context
import androidx.compose.animation.core.animateIntAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.TimetablePrefs
import com.kxin.classtable.data.TimetablePrefsStore
import com.kxin.classtable.data.Weather
import com.kxin.classtable.data.WeatherRepository
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuDialog
import com.kxin.classtable.design.YohakuDialogAction
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuMotion
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.domain.Adjustments
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.weeksText
import com.kxin.classtable.ui.weather.WeatherCard
import com.kxin.classtable.ui.weather.WeatherIcon
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class TimetableViewModel @Inject constructor(
    courseRepository: CourseRepository,
    settingsRepository: SettingsRepository,
    private val weatherRepository: WeatherRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    /**
     * 订阅全部课程而不是「今天星期几」那一批:调休可能让今天去上**别的星期几**的课
     * (周六补周四的课),按星期订阅就取不到了。
     */
    val courses: StateFlow<List<Course>> = courseRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val weather: StateFlow<Weather?> = weatherRepository.weather

    /** 课程块显示偏好(「设置 → 课表显示」)。 */
    val displayPrefs: StateFlow<TimetablePrefs> = TimetablePrefsStore.flow(context)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TimetablePrefs())

    init {
        viewModelScope.launch {
            weatherRepository.refresh()
            while (true) {
                delay(WEATHER_REFRESH_INTERVAL_MS)
                weatherRepository.refresh(force = true)
            }
        }
    }

    fun refreshWeather() {
        viewModelScope.launch { weatherRepository.refresh(force = true) }
    }

    private companion object {
        /** 天气变化慢,半小时一次足够;不必跟着「还有几分钟下课」的 30 秒节拍走。 */
        const val WEATHER_REFRESH_INTERVAL_MS = 30L * 60 * 1000
    }
}

/**
 * 课表:周视图与日视图合并成一个页面,右上角切换。
 *
 * - **周模式**:一屏看全一周七天,左右滑动翻周。
 * - **日模式**:一天一页,左右滑动翻日(页数 = 周数 × 7)。
 *
 * 两种模式共用同一份作息 / 调休解析,并共用顶部左上角的**当前天气图标**(点开是完整天气卡)。
 */
@Composable
fun TimetableScreen(
    nav: NavHostController,
    viewModel: TimetableViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val weather by viewModel.weather.collectAsStateWithLifecycle()
    val prefs by viewModel.displayPrefs.collectAsStateWithLifecycle()

    val weekCount = settings.semesterWeekCount.coerceAtLeast(1)
    val realWeek = Schedule.currentWeek(settings.semesterStartDay, weekCount)
    val periods = remember(settings.periodTimes) { Schedule.parsePeriods(settings.periodTimes) }
    val adjustments = remember(settings.scheduleAdjustments) {
        Adjustments.decode(settings.scheduleAdjustments)
    }
    val today = Schedule.todayWeekday()
    val scope = rememberCoroutineScope()

    var isDayMode by rememberSaveable { mutableStateOf(false) }
    var detailCourse by remember { mutableStateOf<Course?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showWeather by remember { mutableStateOf(false) }

    // 实时动态:每 30 秒刷新「正在上课 / 距下一节」与当前时间线
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
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
            InfoLine(label = "周次", value = course.weeksText(weekCount))
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

    if (showWeather) {
        weather?.let { w ->
            LaunchedEffect(Unit) { viewModel.refreshWeather() }
            YohakuDialog(
                onDismissRequest = { showWeather = false },
                title = "当前天气",
                actions = { YohakuDialogAction(text = "关闭", onClick = { showWeather = false }) },
            ) {
                WeatherCard(weather = w, onRefresh = viewModel::refreshWeather)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        key(isDayMode) {
            val pageCount = if (isDayMode) weekCount * 7 else weekCount
            val initialPage = if (isDayMode) {
                ((realWeek - 1) * 7 + (today - 1)).coerceIn(0, pageCount - 1)
            } else {
                (realWeek - 1).coerceIn(0, weekCount - 1)
            }
            val pagerState = rememberPagerState(initialPage = initialPage) { pageCount }
            // 无论从哪里打开应用,都先落在**当前周 / 今天**:翻页状态是可保存的,
            // 进程重建后会恢复成上次翻到的那一页,所以这里按真实周次把它拨回来。
            LaunchedEffect(realWeek, weekCount, isDayMode) {
                if (pagerState.currentPage != initialPage) pagerState.scrollToPage(initialPage)
            }
            val currentPage = pagerState.currentPage
            val displayedWeek = if (isDayMode) currentPage / 7 + 1 else currentPage + 1
            val weekRange = Schedule.weekRangeText(settings.semesterStartDay, displayedWeek)
            // 日模式下表头跟着「正在看的那一天」走;周模式仍显示今天
            val headerDate = if (!isDayMode) {
                LocalDate.now()
            } else {
                dayDate(settings.semesterStartDay, displayedWeek, currentPage % 7 + 1, today)
            }

            // 顶栏:左上角当前天气图标,右上角今天 / 视图切换 / 添加
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = YohakuDimens.screenPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                weather?.let { w ->
                    // 温度变化时滚动到新值(数字滚动,不是硬跳)
                    val temp by animateIntAsState(
                        targetValue = w.temperature.roundToInt(),
                        animationSpec = YohakuMotion.tween(YohakuMotion.durSlow),
                        label = "weatherTemp",
                    )
                    Row(
                        modifier = Modifier
                            .clickable { showWeather = true }
                            .padding(end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WeatherIcon(weather = w, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$temp°",
                            style = YohakuType.copy13,
                            color = colors.neutral9,
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "今天",
                    style = YohakuType.copy13,
                    color = colors.neutral7,
                    modifier = Modifier
                        .clickable { scope.launch { pagerState.animateScrollToPage(initialPage) } }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    YohakuChip(
                        text = "周",
                        selected = !isDayMode,
                        onClick = { isDayMode = false },
                    )
                    YohakuChip(
                        text = "日",
                        selected = isDayMode,
                        onClick = { isDayMode = true },
                    )
                }
                Text(
                    text = "添加",
                    style = YohakuType.copy13,
                    color = colors.accent,
                    modifier = Modifier
                        .clickable { showAdd = true }
                        .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                )
            }

            // 日期 + 周信息(天气挪到左上角后,日期单独一行)
            Column(
                modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding),
            ) {
                Text(
                    text = "${headerDate.monthValue}月${headerDate.dayOfMonth}日 " +
                        "周${"一二三四五六日"[headerDate.dayOfWeek.value - 1]}",
                    style = YohakuType.title20,
                    color = colors.neutral10,
                )
                Text(
                    text = "第 $displayedWeek 周" +
                        (if (weekRange.isNotEmpty()) " · $weekRange" else "") +
                        (if (settings.semesterStartDay <= 0L) " · 未设置开学日" else ""),
                    style = YohakuType.copy13,
                    color = colors.neutral7,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                beyondViewportPageCount = 1,
            ) { page ->
                if (!isDayMode) {
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
                        WeekdayHeader(
                            dayNumbers = dayNumbers,
                            today = today,
                            marks = marks,
                            // 切周视差:表头比网格移动得稍慢一点,横向拖动时有一点层次
                            modifier = Modifier.graphicsLayer {
                                translationX = pagerState.currentPageOffsetFraction * 56f
                            },
                        )
                        WeekGrid(
                            courses = courses,
                            periods = periods,
                            week = pageWeek,
                            today = today,
                            nowMinute = nowMinute,
                            showNowLine = pageWeek == realWeek,
                            teachingDays = teachingDays,
                            prefs = prefs,
                            onCourseClick = { detailCourse = it },
                            // 网格排在悬浮导航之上,最后一两节不会被导航栏永久压住
                            modifier = Modifier
                                .weight(1f)
                                .padding(bottom = YohakuDimens.navReservedHeight),
                        )
                    }
                } else {
                    val pageWeek = page / 7 + 1
                    val weekday = page % 7 + 1
                    val date = dayDate(settings.semesterStartDay, pageWeek, weekday, today)
                    DayList(
                        date = date,
                        courses = courses,
                        periods = periods,
                        adjustments = adjustments,
                        semesterStartDay = settings.semesterStartDay,
                        semesterWeekCount = settings.semesterWeekCount,
                        nowMinute = nowMinute,
                        isToday = date == LocalDate.now(),
                        onCourseClick = { detailCourse = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * 某一页对应的日期。未设置开学日时以「本周一」为锚往前推 —— 周数无从谈起,但日视图
 * 仍要显示具体某天。
 */
private fun dayDate(semesterStartDay: Long, week: Int, weekday: Int, todayWeekday: Int): LocalDate {
    val dates = Schedule.weekDates(semesterStartDay, week)
    dates.getOrNull(weekday - 1)?.let { return it }
    val thisMonday = LocalDate.now().minusDays((todayWeekday - 1).toLong())
    return thisMonday.plusDays(((week - 1) * 7 + (weekday - 1)).toLong())
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

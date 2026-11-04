package com.kxin.classtable.ui.timetable

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material3.Surface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.AgendaRepository
import com.kxin.classtable.domain.model.AgendaCategory
import com.kxin.classtable.domain.model.AgendaEvent
import com.kxin.classtable.ui.agenda.spans
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.PartnerRepository
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.TimetablePrefs
import com.kxin.classtable.data.TimetableImageExporter
import com.kxin.classtable.data.TimetablePrefsStore
import com.kxin.classtable.data.WeekendDisplay
import com.kxin.classtable.data.Weather
import com.kxin.classtable.data.WeatherRepository
import com.kxin.classtable.design.CoursePalette
import com.kxin.classtable.design.LocalCourseColorScheme
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuDialog
import com.kxin.classtable.design.YohakuDialogAction
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.yohakuNavReservedHeight
import com.kxin.classtable.design.YohakuMotion
import com.kxin.classtable.design.YohakuSegmentedControl
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.rememberEntranceTracker
import com.kxin.classtable.design.rememberYohakuHaptics
import com.kxin.classtable.design.yohakuTouchTarget
import com.kxin.classtable.domain.Adjustments
import com.kxin.classtable.domain.Dates
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.CommonFreeSlot
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.PartnerProfile
import com.kxin.classtable.domain.model.PartnerType
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

/** 双人课表三段视图模式：我的 / TA的 / 双人合盘。 */
enum class TimetableOwnerMode(val label: String) {
    MINE("我的"),
    PARTNER("TA的"),
    OVERLAP("合盘"),
}

@HiltViewModel
class TimetableViewModel @Inject constructor(
    courseRepository: CourseRepository,
    private val partnerRepository: PartnerRepository,
    private val agendaRepository: AgendaRepository,
    settingsRepository: SettingsRepository,
    private val weatherRepository: WeatherRepository,
    val imageExporter: TimetableImageExporter,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    val courses: StateFlow<List<Course>> = courseRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val partnerProfiles: StateFlow<List<PartnerProfile>> = partnerRepository.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activePartner: StateFlow<PartnerProfile?> = partnerRepository.observeActiveProfile()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val partnerCourses: StateFlow<List<Course>> = partnerRepository.observeActivePartnerCourses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val agendas: StateFlow<List<AgendaEvent>> = agendaRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleAgenda(id: String, completed: Boolean) {
        viewModelScope.launch {
            runCatching { agendaRepository.toggleCompleted(id, completed) }
        }
    }

    fun setActivePartner(partnerId: String) {
        viewModelScope.launch {
            partnerRepository.setActivePartner(partnerId)
        }
    }

    fun calculateDayCommonFreeSlots(
        myCourses: List<Course>,
        partnerCourses: List<Course>,
        periods: List<Schedule.Period>,
        week: Int,
        weekday: Int,
        displayWeekday: Int = weekday,
    ): List<CommonFreeSlot> = partnerRepository.calculateCommonFreeSlots(
        myCourses = myCourses,
        partnerCourses = partnerCourses,
        periods = periods,
        week = week,
        weekday = weekday,
        displayWeekday = displayWeekday,
    )

    fun calculateWeekCommonFreeSlots(
        myCourses: List<Course>,
        partnerCourses: List<Course>,
        periods: List<Schedule.Period>,
        week: Int,
        teachingDays: List<Pair<Int, Int>?>,
        visibleDays: List<Int>,
        minPeriods: Int = 2,
    ): List<CommonFreeSlot> = partnerRepository.calculateWeekCommonFreeSlots(
        myCourses = myCourses,
        partnerCourses = partnerCourses,
        periods = periods,
        week = week,
        teachingDays = teachingDays,
        visibleDays = visibleDays,
        minPeriods = minPeriods,
    )

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
    onOpenAssistant: () -> Unit,
    viewModel: TimetableViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val scheme = LocalCourseColorScheme.current
    val dark = CoursePalette.isDarkTheme(colors.paper)
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val partnerProfiles by viewModel.partnerProfiles.collectAsStateWithLifecycle()
    val activePartner by viewModel.activePartner.collectAsStateWithLifecycle()
    val partnerCourses by viewModel.partnerCourses.collectAsStateWithLifecycle()
    val agendas by viewModel.agendas.collectAsStateWithLifecycle()
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

    var isDayMode by rememberSaveable { mutableStateOf(false) }
    var ownerMode by rememberSaveable { mutableStateOf(TimetableOwnerMode.MINE) }
    LaunchedEffect(activePartner) {
        if (activePartner == null && ownerMode != TimetableOwnerMode.MINE) {
            ownerMode = TimetableOwnerMode.MINE
        }
    }

    val displayedCourses = remember(ownerMode, courses, partnerCourses) {
        when (ownerMode) {
            TimetableOwnerMode.MINE -> courses
            TimetableOwnerMode.PARTNER -> partnerCourses
            TimetableOwnerMode.OVERLAP -> courses + partnerCourses
        }
    }

    var detailCourse by remember { mutableStateOf<Course?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var newCourseWeekday by remember { mutableStateOf<Int?>(null) }
    var newCoursePeriod by remember { mutableStateOf<Int?>(null) }
    var showWeather by remember { mutableStateOf(false) }
    var showWeekPicker by remember { mutableStateOf(false) }
    var showExportImage by remember { mutableStateOf(false) }
    var showDateRadar by remember { mutableStateOf(false) }
    var showPartnerSwitcher by remember { mutableStateOf(false) }

    fun navigateToFreeSlotAgenda(date: LocalDate, slot: CommonFreeSlot) {
        val partner = activePartner
        val actionWord = if (partner?.type == PartnerType.COUPLE) "约会" else "自习"
        val defaultTitle = Uri.encode("与 ${partner?.name ?: "TA"} $actionWord")
        nav.navigate(
            "agenda_form?date=${date.toEpochDay()}&category=${AgendaCategory.ACTIVITY.name}" +
                "&startMinute=${slot.startMinute}&endMinute=${slot.endMinute}&title=$defaultTitle",
        )
    }

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
        val isPartnerCourse = course.isPartner
        val dialogTitle = if (isPartnerCourse && !course.ownerName.isNullOrBlank()) {
            "${course.ownerName} · ${course.name}"
        } else {
            course.name
        }
        YohakuDialog(
            onDismissRequest = { detailCourse = null },
            title = dialogTitle,
            actions = {
                YohakuDialogAction(text = "关闭", onClick = { detailCourse = null })
                if (isPartnerCourse) {
                    YohakuDialogAction(
                        text = "双人课表设定",
                        accent = true,
                        onClick = {
                            detailCourse = null
                            nav.navigate("partner")
                        },
                    )
                } else {
                    YohakuDialogAction(
                        text = "查看详情",
                        accent = true,
                        onClick = {
                            detailCourse = null
                            nav.navigate("course_detail/${course.id}")
                        },
                    )
                }
            },
        ) {
            if (isPartnerCourse && !course.ownerName.isNullOrBlank()) {
                InfoLine(label = "归属", value = "${course.ownerName}的课表")
            }
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
        val prefillDay = newCourseWeekday
        val prefillPeriod = newCoursePeriod
        AddDialog(
            targetWeekday = prefillDay,
            targetPeriod = prefillPeriod,
            onDismiss = {
                showAdd = false
                newCourseWeekday = null
                newCoursePeriod = null
            },
            onPick = { route ->
                showAdd = false
                val finalRoute = if (route == "course_form" && prefillDay != null) {
                    val p = prefillPeriod ?: 1
                    "course_form?weekday=$prefillDay&startPeriod=$p&endPeriod=$p"
                } else {
                    route
                }
                newCourseWeekday = null
                newCoursePeriod = null
                nav.navigate(finalRoute)
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

    val dayMode = isDayMode
    val haptics = rememberYohakuHaptics()
    // 入场只演一次:翻页会回收页、回头会重组,没有这层守卫就会每翻一次重播一次
    val entrance = rememberEntranceTracker()

    // 与模式无关的锚点:锚定「第几周 + 星期几」。切周 / 日只是换粒度,不换位置(任务连续);
    // 首次进入仍落在真实当前周 / 今天。用 remember(非 saveable):重建即回到当前周,与既有行为一致。
    //
    // 但 settings 是异步流入的:首帧还是 AppSettings() 默认值(开学日 = 0),此时算出的「当前周」
    // 恒为第 1 周。锚点若只在首帧 remember 一次,真实学期到了也不会再落 —— 冷启动就会停在第 1 周
    // 而不是今天。所以真实开学日到达后再补正一次(瞬时,不演一段从第 1 周滑过去的动画)。
    var anchorWeek by remember { mutableIntStateOf(realWeek) }
    var anchorWeekday by remember { mutableIntStateOf(today) }
    var anchorSynced by remember { mutableStateOf(false) }
    var snapAnchor by remember { mutableStateOf(false) }
    LaunchedEffect(realWeek, settings.semesterStartDay) {
        if (!anchorSynced && settings.semesterStartDay > 0L) {
            anchorSynced = true
            snapAnchor = true
            anchorWeek = realWeek
            anchorWeekday = today
        }
    }
    val atToday = if (dayMode) anchorWeek == realWeek && anchorWeekday == today else anchorWeek == realWeek

    if (showWeekPicker) {
        WeekPickerDialog(
            weekCount = weekCount,
            currentWeek = realWeek,
            selectedWeek = anchorWeek,
            semesterStartDay = settings.semesterStartDay,
            courses = displayedCourses,
            onSelectWeek = { week ->
                showWeekPicker = false
                anchorWeek = week
            },
            onDismiss = { showWeekPicker = false },
        )
    }

    if (showExportImage) {
        TimetableImageDialog(
            initialWeek = anchorWeek,
            weekCount = weekCount,
            imageExporter = viewModel.imageExporter,
            onDismiss = { showExportImage = false },
        )
    }

    if (showPartnerSwitcher) {
        PartnerSwitchDialog(
            profiles = partnerProfiles,
            activeId = activePartner?.id,
            dark = dark,
            onSelect = { id ->
                viewModel.setActivePartner(id)
                showPartnerSwitcher = false
            },
            onManage = {
                showPartnerSwitcher = false
                nav.navigate("partner")
            },
            onDismiss = { showPartnerSwitcher = false },
        )
    }

    if (showMoreMenu) {
        TimetableMoreDialog(
            atToday = atToday,
            hasPartner = activePartner != null,
            onDismiss = { showMoreMenu = false },
            onToday = {
                anchorWeek = realWeek
                anchorWeekday = today
            },
            onAddCourse = {
                newCourseWeekday = null
                newCoursePeriod = null
                showAdd = true
            },
            onAssistant = {
                onOpenAssistant()
            },
            onExportImage = {
                showExportImage = true
            },
            onDateRadar = {
                if (activePartner != null) {
                    showDateRadar = true
                } else {
                    nav.navigate("partner")
                }
            },
            onPartnerManage = {
                nav.navigate("partner")
            },
        )
    }

    if (showDateRadar) {
        val partner = activePartner
        if (partner != null) {
            val radarTeachingDays = remember(
                adjustments,
                settings.semesterStartDay,
                settings.semesterWeekCount,
                anchorWeek,
            ) {
                resolveTeachingDays(
                    adjustments,
                    settings.semesterStartDay,
                    settings.semesterWeekCount,
                    anchorWeek,
                )
            }
            DateRadarDialog(
                week = anchorWeek,
                partner = partner,
                myCourses = courses,
                partnerCourses = partnerCourses,
                periods = periods,
                teachingDays = radarTeachingDays,
                semesterStartDay = settings.semesterStartDay,
                todayWeekday = today,
                viewModel = viewModel,
                onSelectSlot = { slot ->
                    showDateRadar = false
                    val targetDate = dayDate(settings.semesterStartDay, anchorWeek, slot.weekday, today)
                    navigateToFreeSlotAgenda(targetDate, slot)
                },
                onDismiss = { showDateRadar = false },
            )
        } else {
            showDateRadar = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        // 顶栏固定:切换的是内容,不是页头 —— 极致极简:左侧天气,右侧[周/日]分段切换与更多(···)
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
                        .yohakuTouchTarget()
                        .clip(RoundedCornerShape(YohakuDimens.radiusChip))
                        .clickable {
                            haptics.tick()
                            showWeather = true
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
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
            // 视图粒度:周 / 日 互斥视图分段控件
            YohakuSegmentedControl(
                options = listOf("周", "日"),
                selectedIndex = if (isDayMode) 1 else 0,
                onSelect = { isDayMode = it == 1 },
                modifier = Modifier.width(60.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            // 更多功能菜单:今天、新建/导入、AI 助手、导出长图、双人课表/空闲雷达统一收归于此
            HeaderTextAction(text = "···", color = colors.neutral9) {
                haptics.tick()
                showMoreMenu = true
            }
        }

        // 双人课表控制栏:仅在有绑定伴侣/好友时克制展示,未绑定时不占空间
        val currentPartner = activePartner
        if (currentPartner != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = YohakuDimens.screenPadding, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val markColor = CoursePalette.mark(currentPartner.colorHue, dark, scheme)
                val tintColor = CoursePalette.tint(currentPartner.colorHue, dark, scheme)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(YohakuDimens.radiusControl))
                        .background(tintColor)
                        .border(1.dp, colors.line, RoundedCornerShape(YohakuDimens.radiusControl))
                        .clickable {
                            haptics.tick()
                            if (partnerProfiles.size > 1) {
                                showPartnerSwitcher = true
                            } else {
                                nav.navigate("partner")
                            }
                        }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(markColor),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "${currentPartner.name} · ${currentPartner.type.label}",
                        style = YohakuType.label12,
                        color = colors.neutral9,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "▾",
                        style = YohakuType.label12,
                        color = colors.neutral6,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                YohakuSegmentedControl(
                    options = TimetableOwnerMode.entries.map { it.label },
                    selectedIndex = ownerMode.ordinal,
                    onSelect = { idx ->
                        val target = TimetableOwnerMode.entries.getOrElse(idx) { TimetableOwnerMode.MINE }
                        if (target != TimetableOwnerMode.MINE && activePartner == null) {
                            nav.navigate("partner")
                        } else {
                            ownerMode = target
                        }
                    },
                    modifier = Modifier.width(136.dp),
                )
            }
        }

        // 周 / 日:同一份内容换粒度。横向共享轴 —— 退场页整幅滑出、入场页整幅滑入,
        // 同曲线同时长,首尾相接(见文件头铁律:任意时刻屏上只可能有一页的像素)。
        AnimatedContent(
            targetState = isDayMode,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                val forward = targetState
                val spec = YohakuMotion.tween<IntOffset>(YohakuMotion.durSlow, YohakuMotion.easeOut)
                slideInHorizontally(animationSpec = spec) { if (forward) it else -it } togetherWith
                    slideOutHorizontally(animationSpec = spec) { if (forward) -it else it }
            },
            label = "timetableMode",
        ) { dayMode ->
            val pageCount = if (dayMode) weekCount * 7 else weekCount
            val initialPage = if (dayMode) {
                (((anchorWeek - 1) * 7 + (anchorWeekday - 1))).coerceIn(0, pageCount - 1)
            } else {
                (anchorWeek - 1).coerceIn(0, weekCount - 1)
            }
            val pagerState = rememberPagerState(initialPage = initialPage) { pageCount }
            fun pageFor(): Int = if (dayMode) {
                ((anchorWeek - 1) * 7 + (anchorWeekday - 1)).coerceIn(0, pageCount - 1)
            } else {
                (anchorWeek - 1).coerceIn(0, weekCount - 1)
            }
            // 锚点 → 分页:点「今天」或点星期头后,把当前模式的分页滚过去。
            // 开学日补正那一次瞬时跳转 —— 否则冷启动会演一段从第 1 周滑到第 N 周的动画。
            LaunchedEffect(anchorWeek, anchorWeekday, dayMode) {
                val target = pageFor()
                if (pagerState.currentPage != target) {
                    if (snapAnchor) pagerState.scrollToPage(target) else pagerState.animateScrollToPage(target)
                }
                snapAnchor = false
            }
            // 分页 → 锚点:滑动落定后把锚点写成当前所看的位置,切模式时才接得上
            LaunchedEffect(pagerState, dayMode) {
                snapshotFlow { pagerState.settledPage }.collect { p ->
                    if (dayMode) {
                        anchorWeek = p / 7 + 1
                        anchorWeekday = p % 7 + 1
                    } else {
                        anchorWeek = p + 1
                    }
                }
            }
            val currentPage = pagerState.currentPage
            val displayedWeek = if (dayMode) currentPage / 7 + 1 else currentPage + 1
            val weekRange = Schedule.weekRangeText(settings.semesterStartDay, displayedWeek)
            val weekLabel = "第 $displayedWeek 周" +
                (if (weekRange.isNotEmpty()) " · $weekRange" else "") +
                (if (settings.semesterStartDay <= 0L) " · 未设置开学日" else "")

            Column(modifier = Modifier.fillMaxSize()) {
                // 日期 + 周信息(天气挪到左上角后,日期单独一行)。
                // 日模式下这一天由 DayList 逐页绘制(随翻页一起滑动),这里不再画一遍 ——
                // 否则表头与列表会各显示一次同一个日期。
                if (!dayMode) {
                    val noStart = settings.semesterStartDay <= 0L
                    val atCurrentWeek = displayedWeek == realWeek
                    // 大标题随「所看的那一周」走:在当前周亮今天,翻到别的周就亮这一周的日期区间 ——
                    // 原先恒显示今天,翻到第 4 周时表头列已是别的日期,标题却还写着今天,对不上。
                    val title = if (noStart || atCurrentWeek) {
                        Dates.monthDayWeek(LocalDate.now())
                    } else {
                        weekRange.ifEmpty { "第 $displayedWeek 周" }
                    }
                    // 副标题不重复区间:区间已经在标题里时就只剩「第 N 周」。
                    val subtitle = buildString {
                        append("第 ").append(displayedWeek).append(" 周")
                        if (atCurrentWeek && weekRange.isNotEmpty()) append(" · ").append(weekRange)
                        if (noStart) append(" · 未设置开学日")
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = YohakuDimens.screenPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(YohakuDimens.radiusChip))
                                .clickable {
                                    haptics.tick()
                                    showWeekPicker = true
                                }
                                .padding(vertical = 2.dp),
                        ) {
                            Text(
                                text = title,
                                style = YohakuType.title20,
                                color = colors.neutral10,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = subtitle,
                                    style = YohakuType.copy13,
                                    color = colors.neutral7,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "▾",
                                    style = YohakuType.copy13,
                                    color = colors.neutral6,
                                )
                            }
                        }

                        // 方案 A:在第 N 周大标题行右侧，放置克制典雅的「导出」微操作
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(YohakuDimens.radiusControl))
                                .clickable {
                                    haptics.tick()
                                    showExportImage = true
                                },
                            shape = RoundedCornerShape(YohakuDimens.radiusControl),
                            color = colors.raised,
                            border = BorderStroke(1.dp, colors.line),
                        ) {
                            Text(
                                text = "导出",
                                style = YohakuType.label12,
                                color = colors.accent,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.5.dp),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                    beyondViewportPageCount = 1,
                ) { page ->
                    if (!dayMode) {
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
                        val visibleDays = remember(prefs.weekendDisplay, teachingDays, displayedCourses) {
                            when (prefs.weekendDisplay) {
                                WeekendDisplay.SHOW_ALL -> listOf(1, 2, 3, 4, 5, 6, 7)
                                WeekendDisplay.HIDE_WEEKEND -> listOf(1, 2, 3, 4, 5)
                                WeekendDisplay.AUTO_HIDE -> {
                                    val weekendHasClasses = listOf(6, 7).any { d ->
                                        val teaching = teachingDays.getOrNull(d - 1)
                                        teaching != null && displayedCourses.any {
                                            it.isOnWeekday(teaching.second) && it.isActiveOnWeek(teaching.first)
                                        }
                                    }
                                    if (weekendHasClasses) listOf(1, 2, 3, 4, 5, 6, 7) else listOf(1, 2, 3, 4, 5)
                                }
                            }
                        }
                        val pageDates = remember(settings.semesterStartDay, pageWeek) {
                            Schedule.weekDates(settings.semesterStartDay, pageWeek)
                        }
                        val agendaDots = remember(pageDates, agendas) {
                            pageDates.map { d -> agendas.any { !it.completed && it.spans(d) } }
                        }
                        val weekCommonFreeSlots = remember(
                            ownerMode,
                            activePartner,
                            courses,
                            partnerCourses,
                            periods,
                            pageWeek,
                            teachingDays,
                            visibleDays,
                        ) {
                            if (ownerMode == TimetableOwnerMode.OVERLAP && activePartner != null) {
                                viewModel.calculateWeekCommonFreeSlots(
                                    myCourses = courses,
                                    partnerCourses = partnerCourses,
                                    periods = periods,
                                    week = pageWeek,
                                    teachingDays = teachingDays,
                                    visibleDays = visibleDays,
                                    minPeriods = 2,
                                )
                            } else {
                                emptyList()
                            }
                        }
                        Column(modifier = Modifier.fillMaxSize()) {
                            WeekdayHeader(
                                dayNumbers = dayNumbers,
                                today = today,
                                marks = marks,
                                visibleDays = visibleDays,
                                agendaDots = agendaDots,
                                onDayClick = { d ->
                                    anchorWeek = pageWeek
                                    anchorWeekday = d
                                    isDayMode = true
                                },
                                // 切周视差:表头比网格移动得稍慢一点,横向拖动时有一点层次
                                modifier = Modifier.graphicsLayer {
                                    translationX = pagerState.currentPageOffsetFraction * YohakuMotion.headerParallaxPx
                                },
                            )
                            WeekGrid(
                                courses = displayedCourses,
                                periods = periods,
                                week = pageWeek,
                                today = today,
                                nowMinute = nowMinute,
                                showNowLine = pageWeek == realWeek,
                                teachingDays = teachingDays,
                                visibleDays = visibleDays,
                                prefs = prefs,
                                entrance = entrance,
                                onCourseClick = { detailCourse = it },
                                onGridLongClick = { d, p ->
                                    newCourseWeekday = d
                                    newCoursePeriod = p
                                    showAdd = true
                                },
                                showOwnerTag = ownerMode == TimetableOwnerMode.OVERLAP,
                                commonFreeSlots = weekCommonFreeSlots,
                                onFreeSlotClick = { slot ->
                                    val targetDate = dayDate(settings.semesterStartDay, pageWeek, slot.weekday, today)
                                    navigateToFreeSlotAgenda(targetDate, slot)
                                },
                                // 网格排在悬浮导航之上,最后一两节不会被导航栏永久压住
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(bottom = yohakuNavReservedHeight()),
                            )
                        }
                    } else {
                        val pageWeek = page / 7 + 1
                        val weekday = page % 7 + 1
                        val date = dayDate(settings.semesterStartDay, pageWeek, weekday, today)
                        val dayCommonFreeSlots = remember(
                            ownerMode,
                            activePartner,
                            courses,
                            partnerCourses,
                            periods,
                            adjustments,
                            date,
                            pageWeek,
                            weekday,
                        ) {
                            if (ownerMode == TimetableOwnerMode.OVERLAP && activePartner != null) {
                                val effective = Adjustments.teachingDay(
                                    adjustments,
                                    date,
                                    settings.semesterStartDay,
                                    settings.semesterWeekCount,
                                )
                                if (effective != null) {
                                    viewModel.calculateDayCommonFreeSlots(
                                        myCourses = courses,
                                        partnerCourses = partnerCourses,
                                        periods = periods,
                                        week = effective.first,
                                        weekday = effective.second,
                                        displayWeekday = weekday,
                                    )
                                } else {
                                    emptyList()
                                }
                            } else {
                                emptyList()
                            }
                        }
                        DayList(
                            date = date,
                            weekLabel = weekLabel,
                            courses = displayedCourses,
                            periods = periods,
                            adjustments = adjustments,
                            semesterStartDay = settings.semesterStartDay,
                            semesterWeekCount = settings.semesterWeekCount,
                            nowMinute = nowMinute,
                            isToday = date == LocalDate.now(),
                            onCourseClick = { detailCourse = it },
                            onWeekClick = {
                                haptics.tick()
                                showWeekPicker = true
                            },
                            entrance = entrance,
                            modifier = Modifier.weight(1f),
                            agendaEvents = agendas,
                            onToggleAgenda = { id, comp -> viewModel.toggleAgenda(id, comp) },
                            onAgendaClick = { agenda ->
                                nav.navigate("agenda_form?eventId=${agenda.id}&date=${date.toEpochDay()}")
                            },
                            onAddAgenda = { startMin, endMin ->
                                nav.navigate("agenda_form?date=${date.toEpochDay()}&startMinute=$startMin&endMinute=$endMin")
                            },
                            onAddCourse = { d, p ->
                                newCourseWeekday = d
                                newCoursePeriod = p
                                showAdd = true
                            },
                            commonFreeSlots = dayCommonFreeSlots,
                            partnerProfile = activePartner,
                            onFreeSlotClick = { slot ->
                                navigateToFreeSlotAgenda(date, slot)
                            },
                        )
                    }
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

/** 「更多 ⋯」操作菜单弹层：今天定位、新建/导入、AI助手、导出长图、空闲雷达与双人课表管理。 */
@Composable
private fun TimetableMoreDialog(
    atToday: Boolean,
    hasPartner: Boolean,
    onDismiss: () -> Unit,
    onToday: () -> Unit,
    onAddCourse: () -> Unit,
    onAssistant: () -> Unit,
    onExportImage: () -> Unit,
    onDateRadar: () -> Unit,
    onPartnerManage: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    val haptics = rememberYohakuHaptics()
    data class MoreItem(
        val title: String,
        val subtitle: String,
        val enabled: Boolean = true,
        val accent: Boolean = false,
        val action: () -> Unit,
    )

    val items = buildList {
        add(
            MoreItem(
                title = "回到今天",
                subtitle = if (atToday) "当前已在今天" else "定位到当前周与今天",
                enabled = !atToday,
                action = {
                    haptics.tick()
                    onDismiss()
                    onToday()
                },
            ),
        )
        add(
            MoreItem(
                title = "新建与导入课程",
                subtitle = "手动添加 / 教务导入 / 表格或 AI 导入",
                accent = true,
                action = {
                    haptics.tick()
                    onDismiss()
                    onAddCourse()
                },
            ),
        )
        add(
            MoreItem(
                title = "✦ AI 助手",
                subtitle = "自然语言调课、加日程、查作息",
                accent = true,
                action = {
                    haptics.tick()
                    onDismiss()
                    onAssistant()
                },
            ),
        )
        add(
            MoreItem(
                title = "导出课表长图",
                subtitle = "生成纸感美学壁纸与分享图片",
                action = {
                    haptics.tick()
                    onDismiss()
                    onExportImage()
                },
            ),
        )
        if (hasPartner) {
            add(
                MoreItem(
                    title = "空闲交集雷达",
                    subtitle = "对比双方无课时段，一键发起约会/自习",
                    action = {
                        haptics.tick()
                        onDismiss()
                        onDateRadar()
                    },
                ),
            )
        }
        add(
            MoreItem(
                title = "双人课表管理",
                subtitle = if (hasPartner) "切换伴侣、改色、口令分享或解绑" else "生成分享口令或绑定朋友/情侣",
                action = {
                    haptics.tick()
                    onDismiss()
                    onPartnerManage()
                },
            ),
        )
    }

    YohakuDialog(
        onDismissRequest = onDismiss,
        title = "更多功能",
        actions = { YohakuDialogAction(text = "关闭", onClick = onDismiss) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEachIndexed { index, item ->
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
                        .clip(RoundedCornerShape(YohakuDimens.radiusControl))
                        .then(if (item.enabled) Modifier.clickable(onClick = item.action) else Modifier)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = YohakuType.copy15,
                            color = when {
                                !item.enabled -> colors.neutral5
                                item.accent -> colors.accent
                                else -> colors.neutral10
                            },
                        )
                        Text(
                            text = item.subtitle,
                            style = YohakuType.label12,
                            color = if (item.enabled) colors.neutral7 else colors.neutral5,
                        )
                    }
                    if (item.enabled) {
                        Text(text = "›", style = YohakuType.copy15, color = colors.neutral6)
                    }
                }
            }
        }
    }
}

/** 「添加 / 新建课程」弹层:教务导入 / 手动添加 / 手动表格导入 / AI 图片导入 / 双人课表。 */
@Composable
private fun AddDialog(
    targetWeekday: Int? = null,
    targetPeriod: Int? = null,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val colors = LocalYohakuColors.current
    val title = if (targetWeekday != null && targetPeriod != null) {
        "新建课程 · ${Dates.weekdayChar(targetWeekday)} 第 $targetPeriod 节"
    } else if (targetWeekday != null) {
        "新建课程 · ${Dates.weekdayChar(targetWeekday)}"
    } else {
        "添加"
    }
    val manualDesc = if (targetWeekday != null && targetPeriod != null) {
        "在 ${Dates.weekdayChar(targetWeekday)} 第 $targetPeriod 节自己填一门课"
    } else {
        "自己填一门课"
    }
    val options = listOf(
        Triple("手动添加", manualDesc, "course_form"),
        Triple("教务导入", "3 步导入,选学校识别方案", "import"),
        Triple("手动表格导入", "粘贴表格或选 Excel 文件", "import_manual"),
        Triple("AI 图片导入", "截图自动识别课表", "import_ai"),
        Triple("双人课表 · 分享与合盘", "生成分享口令或导入朋友/情侣课表", "partner"),
    )
    YohakuDialog(
        onDismissRequest = onDismiss,
        title = title,
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

/** 多伴侣/好友快速切换弹窗。 */
@Composable
private fun PartnerSwitchDialog(
    profiles: List<PartnerProfile>,
    activeId: String?,
    dark: Boolean,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    val scheme = LocalCourseColorScheme.current
    YohakuDialog(
        onDismissRequest = onDismiss,
        title = "切换双人课表对象",
        actions = {
            YohakuDialogAction(text = "取消", onClick = onDismiss)
            YohakuDialogAction(text = "管理与导入", accent = true, onClick = onManage)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            profiles.forEach { p ->
                val selected = p.id == activeId
                val markColor = CoursePalette.mark(p.colorHue, dark, scheme)
                val tintColor = CoursePalette.tint(p.colorHue, dark, scheme)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(YohakuDimens.radiusControl))
                        .background(if (selected) tintColor else colors.neutral2)
                        .border(
                            width = 1.dp,
                            color = if (selected) markColor else colors.line,
                            shape = RoundedCornerShape(YohakuDimens.radiusControl),
                        )
                        .clickable { onSelect(p.id) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(markColor),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${p.name} · ${p.type.label}",
                        style = YohakuType.copy15,
                        color = colors.neutral10,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Text(
                            text = "当前",
                            style = YohakuType.label12,
                            color = colors.accent,
                        )
                    }
                }
            }
        }
    }
}

/** 空闲交集雷达弹窗：扫描整周双方共同无课的时段，点击一键发起约会或自习日程。 */
@Composable
private fun DateRadarDialog(
    week: Int,
    partner: PartnerProfile,
    myCourses: List<Course>,
    partnerCourses: List<Course>,
    periods: List<Schedule.Period>,
    teachingDays: List<Pair<Int, Int>?>,
    semesterStartDay: Long,
    todayWeekday: Int,
    viewModel: TimetableViewModel,
    onSelectSlot: (CommonFreeSlot) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    val haptics = rememberYohakuHaptics()
    var minPeriods by rememberSaveable { mutableIntStateOf(2) }
    val actionWord = if (partner.type == PartnerType.COUPLE) "约会" else "自习"

    val slots = remember(myCourses, partnerCourses, periods, week, teachingDays, minPeriods) {
        viewModel.calculateWeekCommonFreeSlots(
            myCourses = myCourses,
            partnerCourses = partnerCourses,
            periods = periods,
            week = week,
            teachingDays = teachingDays,
            visibleDays = (1..7).toList(),
            minPeriods = minPeriods,
        )
    }

    YohakuDialog(
        onDismissRequest = onDismiss,
        title = "第 $week 周 · 空闲交集雷达",
        actions = {
            YohakuDialogAction(text = "关闭", onClick = onDismiss)
        },
    ) {
        Column {
            Text(
                text = "已对比你与「${partner.name}」第 $week 周的课程排期，点选任一共同空闲时段即可一键发起${actionWord}日程：",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YohakuChip(
                    text = "连堂空闲 (≥2节)",
                    selected = minPeriods == 2,
                    onClick = { minPeriods = 2 },
                )
                YohakuChip(
                    text = "全部空闲 (≥1节)",
                    selected = minPeriods == 1,
                    onClick = { minPeriods = 1 },
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (slots.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "本周暂无符合条件的共同空闲时段",
                        style = YohakuType.copy14,
                        color = colors.neutral6,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    slots.forEach { slot ->
                        val slotDate = dayDate(semesterStartDay, week, slot.weekday, todayWeekday)
                        val h = slot.durationMinutes / 60
                        val m = slot.durationMinutes % 60
                        val durationStr = if (h > 0 && m > 0) "${h}小时${m}分" else if (h > 0) "${h}小时" else "${m}分钟"
                        val periodStr = if (slot.startPeriod == slot.endPeriod) {
                            "第${slot.startPeriod}节"
                        } else {
                            "第${slot.startPeriod}-${slot.endPeriod}节"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(YohakuDimens.radiusControl))
                                .background(colors.neutral2)
                                .border(1.dp, colors.line, RoundedCornerShape(YohakuDimens.radiusControl))
                                .clickable {
                                    haptics.tick()
                                    onSelectSlot(slot)
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${Course.weekdayName(slot.weekday)} (${slotDate.monthValue}/${slotDate.dayOfMonth}) · $periodStr",
                                    style = YohakuType.copy14,
                                    color = colors.neutral10,
                                )
                                Text(
                                    text = "%02d:%02d–%02d:%02d · 共 $durationStr".format(
                                        slot.startMinute / 60, slot.startMinute % 60,
                                        slot.endMinute / 60, slot.endMinute % 60,
                                    ),
                                    style = YohakuType.label12,
                                    color = colors.neutral6,
                                )
                            }
                            Text(
                                text = "＋ 发起$actionWord",
                                style = YohakuType.label12,
                                color = colors.accent,
                            )
                        }
                    }
                }
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

/**
 * 页头里的纯文字动作(今天 / 添加 / ✦ 助手)。
 *
 * 用 [Box] + [Alignment.Center] 兜住 44dp 的触达高度:把 `defaultMinSize` 直接挂在 [Text] 上时,
 * 文字会**顶对齐**在那 44dp 里(下方空出十几 dp),于是和同一行的分段控件(固定 34dp,文字居中)、
 * 天气图标(22dp)的中线错开约 8dp —— 整排读起来就是「不齐平」。居中的 Box 让字形落回行中线,
 * 触达高度不变。
 */
@Composable
private fun HeaderTextAction(
    text: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .yohakuTouchTarget()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = YohakuType.copy13, color = color)
    }
}
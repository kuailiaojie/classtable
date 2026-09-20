package com.kxin.classtable.ui.courses

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.courseMark
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.WeekType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class CourseDetailViewModel @Inject constructor(
    private val courseRepository: CourseRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    /** 课程列表流:详情页响应式呈现;课程被删(本页或编辑页)时该课程随即从列表消失。 */
    val courses: StateFlow<List<Course>> = courseRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun delete(id: String) {
        viewModelScope.launch { runCatching { courseRepository.delete(id) } }
    }
}

/** 课程详情:信息 + 排期(周次可视化)。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CourseDetailScreen(
    nav: NavHostController,
    courseId: String,
    viewModel: CourseDetailViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val periods = remember(settings.periodTimes) { Schedule.parsePeriods(settings.periodTimes) }

    // 响应式:课程存在过之后一旦从列表消失(本页删除 / 编辑页删除),立即返回上一层。
    // hadCourse 用 rememberSaveable,保证「进编辑页删除后返回」也能识别。
    val course = courses.firstOrNull { it.id == courseId }
    var hadCourse by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(course) {
        if (course != null) {
            hadCourse = true
        } else if (hadCourse) {
            nav.popBackStack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(title = "课程详情", onBack = { nav.popBackStack() })

        val c = course
        if (c == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "课程不存在或已删除", style = YohakuType.copy14, color = colors.neutral7)
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = YohakuDimens.screenPadding),
            ) {
                Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
                // 课程色标:与周视图/课程列表同一枚淡彩,方便对上号
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .height(4.dp)
                        .background(courseMark(c)),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = c.name, style = YohakuType.title24, color = colors.neutral10)
                Spacer(modifier = Modifier.height(YohakuDimens.gapTight))

                InfoRow("星期", Course.weekdaysText(c))
                if (c.hasCustomTime()) {
                    InfoRow("时间", "自定义 · ${Schedule.courseTimeText(c)}")
                } else {
                    // 节次与时刻分开写:「第 3-4 节」和「10:10–12:00」各自独立,避免混读
                    InfoRow("节次", "第 ${c.startPeriod}-${c.endPeriod} 节")
                    InfoRow("时间", Schedule.periodRange(periods, c.startPeriod, c.endPeriod))
                }
                if (c.teacher.isNotBlank()) InfoRow("教师", c.teacher)
                if (c.location.isNotBlank()) InfoRow("地点", c.location)
                if (c.note.isNotBlank()) InfoRow("备注", c.note)
                InfoRow("周次", weekPatternText(c))

                Spacer(modifier = Modifier.height(YohakuDimens.gapCard))

                Text(text = "排期", style = YohakuType.title20, color = colors.neutral10)
                Spacer(modifier = Modifier.height(6.dp))
                val scheduleDates = courseDates(c, settings.semesterStartDay, settings.semesterWeekCount)
                if (scheduleDates.isEmpty()) {
                    Text(
                        text = "在「设置 → 学期周次」里设定学期起始日后,这里会列出每节课的具体日期。",
                        style = YohakuType.label12,
                        color = colors.neutral7,
                    )
                } else {
                    Text(
                        text = "共 ${scheduleDates.size} 次 · ${Schedule.courseTimeText(c, periods)}",
                        style = YohakuType.label12,
                        color = colors.neutral7,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        scheduleDates.forEach { (date, weekNo) ->
                            val active = c.isActiveOnWeek(weekNo)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (active) colors.accent else colors.neutral3)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = "${date.monthValue}/${date.dayOfMonth} 周${Course.WEEKDAY_CHARS[date.dayOfWeek.value - 1]}",
                                    style = YohakuType.label12,
                                    color = if (active) androidx.compose.ui.graphics.Color.White else colors.neutral7,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

                Text(
                    text = "编辑",
                    style = YohakuType.copy13,
                    color = colors.accent,
                    modifier = Modifier
                        .clickable { nav.navigate("course_form?courseId=${c.id}") }
                        .padding(vertical = 8.dp),
                )
                Text(
                    text = "删除课程",
                    style = YohakuType.copy13,
                    color = colors.error,
                    modifier = Modifier
                        .clickable { viewModel.delete(c.id) }
                        .padding(vertical = 8.dp),
                )

                Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = LocalYohakuColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = YohakuType.label12,
            color = colors.neutral7,
            modifier = Modifier.width(64.dp),
        )
        Text(text = value, style = YohakuType.copy15, color = colors.neutral9)
    }
}

private fun weekPatternText(course: Course): String = when (course.weekType) {
    WeekType.EVERY_WEEK -> "每周"
    WeekType.ODD_WEEK -> "单周(1,3,5…)"
    WeekType.EVEN_WEEK -> "双周(2,4,6…)"
    WeekType.CUSTOM -> "第${course.weekStart}-${course.weekEnd}周"
}

/** 课程在学期内的所有上课日期(需已设置学期起始日;未设置返回空列表)。周一对齐,与周视图一致。 */
private fun courseDates(course: Course, semesterStartDay: Long, weekCount: Int): List<Pair<LocalDate, Int>> {
    if (semesterStartDay <= 0L) return emptyList()
    val count = weekCount.coerceIn(1, 30)
    val startMonday = Schedule.mondayEpochDay(semesterStartDay)
    return buildList {
        for (w in 1..count) {
            if (!course.isActiveOnWeek(w)) continue
            for (d in 1..7) {
                if (!course.isOnWeekday(d)) continue
                add(LocalDate.ofEpochDay(startMonday + (w - 1) * 7L + (d - 1)) to w)
            }
        }
    }
}

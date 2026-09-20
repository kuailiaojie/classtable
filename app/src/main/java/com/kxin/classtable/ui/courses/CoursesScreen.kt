package com.kxin.classtable.ui.courses

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.kxin.classtable.design.YohakuBottomNav
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.courseMark
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.ui.navigateToTab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class CoursesViewModel @Inject constructor(
    courseRepository: CourseRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val courses: StateFlow<List<Course>> = courseRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
}

/**
 * 课程列表:按星期分组(周一…周日),一门课在它的每个上课日各出现一次。
 * 每行左边是课程色标,右边给出节次与具体时刻;点击进详情。
 */
@Composable
fun CoursesScreen(
    nav: NavHostController,
    viewModel: CoursesViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val periods = remember(settings.periodTimes) { Schedule.parsePeriods(settings.periodTimes) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(title = "课程")

        if (courses.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "还没有课程",
                        style = YohakuType.copy14,
                        color = colors.neutral7,
                    )
                    Text(
                        text = "点周视图右上角「添加」:教务导入、手动添加或图片识别",
                        style = YohakuType.label12,
                        color = colors.neutral6,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = YohakuDimens.screenPadding,
                    end = YohakuDimens.screenPadding,
                    bottom = YohakuDimens.gapCard,
                ),
            ) {
                (1..7).forEach { day ->
                    val dayCourses = courses
                        .filter { it.isOnWeekday(day) }
                        .sortedBy { Schedule.courseStartMinute(it, periods) ?: Int.MAX_VALUE }
                    if (dayCourses.isEmpty()) return@forEach

                    item(key = "head-$day") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = YohakuDimens.gapCard, bottom = 4.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Text(
                                text = "周${Course.WEEKDAY_CHARS[day - 1]}",
                                style = YohakuType.title20,
                                color = colors.neutral10,
                            )
                            Text(
                                text = "  ${dayCourses.size} 门",
                                style = YohakuType.label12,
                                color = colors.neutral7,
                                modifier = Modifier.padding(bottom = 3.dp),
                            )
                        }
                    }

                    itemsIndexed(dayCourses, key = { _, c -> "$day-${c.id}" }) { index, course ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { nav.navigate("course_detail/${course.id}") }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(30.dp)
                                    .clip(RoundedCornerShape(YohakuDimens.radiusChip))
                                    .background(courseMark(course)),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = course.name,
                                    style = YohakuType.copy15,
                                    color = colors.neutral10,
                                )
                                val meta = listOf(course.teacher, course.location)
                                    .filter { it.isNotBlank() }.joinToString(" · ")
                                if (meta.isNotEmpty()) {
                                    Text(
                                        text = meta,
                                        style = YohakuType.label12,
                                        color = colors.neutral7,
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = if (course.hasCustomTime()) {
                                        "自定义"
                                    } else {
                                        "第 ${course.startPeriod}-${course.endPeriod} 节"
                                    },
                                    style = YohakuType.timeMono,
                                    color = colors.neutral7,
                                )
                                Text(
                                    text = Schedule.courseTimeText(course, periods),
                                    style = YohakuType.timeMono,
                                    color = colors.neutral6,
                                )
                            }
                            Text(
                                text = "›",
                                style = YohakuType.copy15,
                                color = colors.neutral6,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        if (index < dayCourses.size - 1) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(colors.neutral3),
                            )
                        }
                    }
                }
            }
        }

        YohakuBottomNav(current = "courses", onNavigate = { nav.navigateToTab(it) })
    }
}

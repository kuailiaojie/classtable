package com.kxin.classtable.ui.courses

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuBottomNav
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.domain.Schedule
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
) : ViewModel() {
    val courses: StateFlow<List<Course>> = courseRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

/** 课程列表:全部课程,点击进入详情。 */
@Composable
fun CoursesScreen(
    nav: NavHostController,
    viewModel: CoursesViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val courses by viewModel.courses.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(title = "课程", onBack = { nav.popBackStack() })

        if (courses.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "还没有课程,去添加或导入吧",
                    style = YohakuType.copy14,
                    color = colors.neutral7,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = YohakuDimens.screenPadding),
            ) {
                items(courses, key = { it.id }) { course ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { nav.navigate("course_detail/${course.id}") }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = Course.weekdaysShort(course),
                            style = YohakuType.timeMono,
                            color = colors.neutral7,
                            modifier = Modifier.width(44.dp),
                        )
                        Text(
                            text = Schedule.courseTimeText(course),
                            style = YohakuType.timeMono,
                            color = colors.neutral7,
                            modifier = Modifier.width(90.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = course.name, style = YohakuType.copy15, color = colors.neutral10)
                            val meta = listOf(course.teacher, course.location)
                                .filter { it.isNotBlank() }.joinToString(" · ")
                            if (meta.isNotEmpty()) {
                                Text(text = meta, style = YohakuType.label12, color = colors.neutral7)
                            }
                        }
                        Text(text = "›", style = YohakuType.copy15, color = colors.neutral6)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.neutral3),
                    )
                }
            }
        }

        YohakuBottomNav(current = "courses", onNavigate = { nav.navigateToTab(it) })
    }
}

package com.kxin.classtable.ui.yuketang

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.local.YuketangBindingEntity
import com.kxin.classtable.data.yuketang.NotLoggedInException
import com.kxin.classtable.data.yuketang.YuketangCourse
import com.kxin.classtable.data.yuketang.YuketangMatcher
import com.kxin.classtable.data.yuketang.YuketangRepository
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuDialog
import com.kxin.classtable.design.YohakuDialogAction
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.ui.settings.DividerLine
import com.kxin.classtable.ui.settings.SettingRow
import com.kxin.classtable.ui.settings.SettingRowWithSubtitle
import com.kxin.classtable.ui.settings.SettingsSection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class YuketangBindViewModel @Inject constructor(
    private val repository: YuketangRepository,
    courseRepository: CourseRepository,
) : ViewModel() {

    val courses: StateFlow<List<Course>> = courseRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** courseId → 绑定。 */
    val bindings: StateFlow<Map<String, YuketangBindingEntity>> = repository.observeBindings()
        .map { list -> list.associateBy { it.courseId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val classroomsState = MutableStateFlow<List<YuketangCourse>>(emptyList())
    val classrooms: StateFlow<List<YuketangCourse>> = classroomsState.asStateFlow()

    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun loadClassrooms() = viewModelScope.launch {
        if (!repository.isLoggedIn()) {
            message = "请先登录雨课堂"
            return@launch
        }
        loading = true
        runCatching { repository.classrooms() }
            .onSuccess {
                classroomsState.value = it
                message = if (it.isEmpty()) "雨课堂没有返回课程(本学期可能没有在用雨课堂的课)" else null
            }
            .onFailure {
                message = if (it is NotLoggedInException) {
                    "登录已失效,请重新登录"
                } else {
                    "拉取课程列表失败:${it.message ?: "网络错误"}"
                }
            }
        loading = false
    }

    /** 按课名/教师自动补绑(已有绑定不动)。 */
    fun autoMatch() = viewModelScope.launch {
        if (!repository.isLoggedIn()) {
            message = "请先登录雨课堂"
            return@launch
        }
        loading = true
        runCatching { repository.refreshBindings() }
            .onSuccess { count ->
                if (classroomsState.value.isEmpty()) classroomsState.value = runCatching { repository.classrooms() }.getOrDefault(emptyList())
                message = if (count == 0) "没有新的可自动匹配的课程,请手动指定" else "已自动匹配 $count 门课"
            }
            .onFailure {
                message = "自动匹配失败:${it.message ?: "网络错误"}"
            }
        loading = false
    }

    fun bind(courseId: String, classroom: YuketangCourse) = viewModelScope.launch {
        repository.bind(courseId, classroom)
        message = null
    }

    fun unbind(courseId: String) = viewModelScope.launch {
        repository.unbind(courseId)
        message = "已取消绑定"
    }
}

/** 课程 ↔ 雨课堂班级对应关系:自动匹配 + 手动兜底(同名课必须人工确认)。 */
@Composable
fun YuketangBindScreen(
    nav: NavHostController,
    viewModel: YuketangBindViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val classrooms by viewModel.classrooms.collectAsStateWithLifecycle()
    val bindings by viewModel.bindings.collectAsStateWithLifecycle()

    var picking by remember { mutableStateOf<Course?>(null) }

    picking?.let { course ->
        val bound = bindings[course.id]
        // 候选按匹配分排序:最可能的就是用户想选的那个
        val ordered = remember(course.id, classrooms, bound?.classroomId) {
            classrooms.sortedByDescending { YuketangMatcher.score(course, it) }
        }
        YohakuDialog(
            onDismissRequest = { picking = null },
            title = course.name,
            actions = {
                if (bound != null) {
                    YohakuDialogAction(
                        text = "取消绑定",
                        onClick = {
                            viewModel.unbind(course.id)
                            picking = null
                        },
                    )
                }
                YohakuDialogAction(text = "关闭", accent = true, onClick = { picking = null })
            },
            content = {
                Text(
                    text = if (ordered.isEmpty()) {
                        "还没拿到雨课堂课程列表。先在本页点「加载课程列表」。"
                    } else {
                        "选择这门课对应的雨课堂班级(同名课程会有多个班级,需人工确认)。"
                    },
                    style = YohakuType.label12,
                    color = colors.neutral7,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ordered.forEachIndexed { index, classroom ->
                        if (index > 0) DividerLine()
                        val isBound = classroom.classroomId == bound?.classroomId
                        Text(
                            text = (if (isBound) "✓ " else "") + classroom.label(),
                            style = YohakuType.copy14,
                            color = if (isBound) colors.accent else colors.neutral9,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.bind(course.id, classroom)
                                    picking = null
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                        )
                    }
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper)
            .verticalScroll(rememberScrollState()),
    ) {
        YohakuTopBar(title = "课程对应关系", onBack = { nav.popBackStack() })

        SettingsSection(title = "雨课堂班级") {
            SettingRow(
                title = "加载课程列表",
                value = when {
                    viewModel.loading -> "加载中…"
                    classrooms.isEmpty() -> "未加载"
                    else -> "${classrooms.size} 个班级"
                },
                onClick = { if (!viewModel.loading) viewModel.loadClassrooms() },
            )
            DividerLine()
            SettingRow(
                title = "重新自动匹配",
                value = "按课名与教师",
                onClick = { if (!viewModel.loading) viewModel.autoMatch() },
            )
        }

        SettingsSection(title = "课程对应关系") {
            if (courses.isEmpty()) {
                Text(
                    text = "还没有课程。先在课表里添加或导入课程,再回来绑定。",
                    style = YohakuType.label12,
                    color = colors.neutral7,
                    modifier = Modifier.padding(14.dp),
                )
            } else {
                courses.forEachIndexed { index, course ->
                    if (index > 0) DividerLine()
                    val binding = bindings[course.id]
                    val match = remember(course.id, classrooms) {
                        YuketangMatcher.match(listOf(course), classrooms).firstOrNull()
                    }
                    SettingRowWithSubtitle(
                        title = course.name,
                        subtitle = when {
                            binding != null ->
                                "已绑定:" + binding.classroomName.ifBlank { binding.classroomId }
                            match?.best != null ->
                                "自动匹配:${match.best.label()}"
                            (match?.candidates?.size ?: 0) > 1 ->
                                "待确认:${match!!.candidates.size} 个候选"
                            else -> "未匹配"
                        },
                        onClick = { picking = course },
                    )
                }
            }
        }

        viewModel.message?.let { hint ->
            Text(
                text = hint,
                style = YohakuType.label12,
                color = colors.neutral7,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

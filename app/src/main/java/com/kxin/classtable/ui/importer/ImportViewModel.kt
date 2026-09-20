package com.kxin.classtable.ui.importer

import android.util.Log
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.importer.ImportParser
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.Course
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
class ImportViewModel @Inject constructor(
    private val courseRepository: CourseRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _parsedCourses = MutableStateFlow<List<Course>>(emptyList())
    val parsedCourses: StateFlow<List<Course>> = _parsedCourses.asStateFlow()

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _imported = MutableStateFlow(false)
    val imported: StateFlow<Boolean> = _imported.asStateFlow()

    /** 脚本识别到的学校作息("start-end,...");只记录,是否覆盖本地作息由用户在确认页决定。 */
    private val _detectedPeriods = MutableStateFlow<String?>(null)
    val detectedPeriods: StateFlow<String?> = _detectedPeriods.asStateFlow()

    /** 脚本识别到的学期配置:(周数, 开学日 epochDay)。 */
    private val _detectedSemester = MutableStateFlow<Pair<Int, Long>?>(null)
    val detectedSemester: StateFlow<Pair<Int, Long>?> = _detectedSemester.asStateFlow()

    /**
     * 当前生效的作息。确认页在「不应用脚本作息」时要用它算课程时间 ——
     * 否则预览里的课程时间会用 App 内置的默认表算,和脚本识别的作息对不上。
     */
    val currentPeriodTimes: StateFlow<String> = settingsRepository.settings
        .map { it.periodTimes }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Schedule.DEFAULT_PERIODS)

    fun onCoursesJson(json: String) {
        val result = ImportParser.parseCourses(json)
        _parsedCourses.value = result.courses
        when {
            result.error != null -> onToast("课程数据解析失败:${result.error}")
            result.courses.isEmpty() && result.received > 0 ->
                onToast("收到 ${result.received} 条记录但都无法识别,请确认已在课表页面执行导入")
            result.dropped > 0 ->
                onToast("已解析 ${result.courses.size} 门,跳过 ${result.dropped} 条无法识别的记录")
        }
    }

    fun onDone() {
        _done.value = true
    }

    fun consumeDone() {
        _done.value = false
    }

    fun onToast(message: String) {
        _toast.value = message
    }

    fun clearToast() {
        _toast.value = null
    }

    fun onPresetTimeSlots(json: String) {
        val periods = ImportParser.parseTimeSlots(json)
        if (periods == null) {
            // 之前静默丢弃:适配器确实抓到了作息、只是格式没被识别时,用户只会看到
            // 「脚本未提供作息时间」而无法判断原因。留痕并给出可操作的提示。
            Log.w("ImportViewModel", "作息时间解析失败,原始数据: ${json.replace('\n', ' ').take(300)}")
            onToast("脚本返回的作息时间无法识别,已保留原作息")
            return
        }
        _detectedPeriods.value = periods
    }

    fun onCourseConfig(json: String) {
        ImportParser.parseCourseConfig(json)?.let { _detectedSemester.value = it }
    }

    /** 应用脚本识别到的作息与学期配置(用户在确认页确认后调用)。 */
    private suspend fun applyDetected() {
        _detectedPeriods.value?.let { settingsRepository.setPeriodTimes(it) }
        _detectedSemester.value?.let { (weeks, startDay) -> settingsRepository.setSemester(startDay, weeks) }
    }

    /** 只应用作息/学期,不导入课程(脚本未给课程时的兜底入口)。 */
    fun applyDetectedOnly() {
        viewModelScope.launch {
            applyDetected()
            _imported.value = true
        }
    }

    fun importAll(applyDetectedConfig: Boolean) {
        viewModelScope.launch {
            if (applyDetectedConfig) applyDetected()
            courseRepository.importAll(_parsedCourses.value)
            _imported.value = true
        }
    }
}

/** WebView 持有者:供 ImportBridge 与 StepLogin 共享同一实例。 */
class WebViewHolder {
    lateinit var webView: WebView

    /**
     * `window.open` / `target=_blank` 打开的新窗口页面。
     * 很多教务系统把课表放在新窗口里,不接管的话点下去什么都不会发生(看起来像白屏)。
     */
    var popup: WebView? = null

    /** 当前 WebView 对应的适配器 id:一致则复用(不丢登录态),不同才重建。 */
    var adapterKey: String? = null

    /** 适配脚本与弹窗应作用于用户此刻看到的页面(有弹窗时就是弹窗)。 */
    val active: WebView get() = popup ?: webView
}

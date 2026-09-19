package com.kxin.classtable.ui.importer

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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun onCoursesJson(json: String) {
        _parsedCourses.value = ImportParser.parseCourses(json)
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
        viewModelScope.launch {
            ImportParser.parseTimeSlots(json)?.let { settingsRepository.setPeriodTimes(it) }
        }
    }

    fun onCourseConfig(json: String) {
        viewModelScope.launch {
            ImportParser.parseCourseConfig(json)?.let { (weeks, startDay) ->
                settingsRepository.setSemester(startDay, weeks)
                // 导入开学日期后同样校准当前周,否则周视图/单双周仍按旧周显示
                if (startDay > 0L) {
                    settingsRepository.setCurrentWeek(Schedule.currentWeek(startDay, weeks.coerceAtLeast(1)))
                }
            }
        }
    }

    fun importAll() {
        viewModelScope.launch {
            courseRepository.importAll(_parsedCourses.value)
            _imported.value = true
        }
    }
}

/** WebView 持有者:供 ImportBridge 与 StepLogin 共享同一实例。 */
class WebViewHolder {
    lateinit var webView: WebView

    /** 当前 WebView 对应的适配器 id:一致则复用(不丢登录态),不同才重建。 */
    var adapterKey: String? = null
}

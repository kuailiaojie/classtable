package com.kxin.classtable.ui.importer

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.AiClient
import com.kxin.classtable.data.AiScheduleResult
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.weeksText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AiUiState {
    data object Idle : AiUiState
    data object Loading : AiUiState
    data class Done(val count: Int, val timeApplied: Boolean) : AiUiState
    data class Error(val message: String) : AiUiState
}

@HiltViewModel
class AiImportViewModel @Inject constructor(
    private val courseRepository: CourseRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _state = MutableStateFlow<AiUiState>(AiUiState.Idle)
    val state: StateFlow<AiUiState> = _state.asStateFlow()

    private val _result = MutableStateFlow<AiScheduleResult?>(null)
    val result: StateFlow<AiScheduleResult?> = _result.asStateFlow()

    private val _imported = MutableStateFlow(false)
    val imported: StateFlow<Boolean> = _imported.asStateFlow()

    fun analyze(settings: AppSettings, imageBase64: String, mimeType: String) {
        viewModelScope.launch {
            _state.value = AiUiState.Loading
            AiClient.extractSchedule(
                provider = settings.provider(),
                apiKey = settings.aiApiKey,
                baseUrl = settings.aiBaseUrl,
                model = settings.aiModel,
                imageBase64 = imageBase64,
                mimeType = mimeType,
                weekCount = settings.semesterWeekCount,
            )
                .onSuccess { r ->
                    _result.value = r
                    r.periodTimes?.let { settingsRepository.setPeriodTimes(it) }
                    _state.value = AiUiState.Done(r.courses.size, r.periodTimes != null)
                }
                .onFailure { e ->
                    _state.value = AiUiState.Error(e.message ?: "解析失败")
                }
        }
    }

    fun importAll() {
        viewModelScope.launch {
            courseRepository.importAll(_result.value?.courses ?: emptyList())
            _imported.value = true
        }
    }
}

/** AI 图片导入:拍摄/选择课表图片 → 配置的 AI 供应商识别(Gemini / OpenAI 兼容)→ 预览 → 导入。 */
@Composable
fun AiImportScreen(
    nav: NavHostController,
    viewModel: AiImportViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val imported by viewModel.imported.collectAsStateWithLifecycle()

    LaunchedEffect(imported) { if (imported) nav.popBackStack() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null && settings.aiApiKey.isNotBlank()) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            if (bytes != null) {
                viewModel.analyze(
                    settings,
                    Base64.encodeToString(bytes, Base64.NO_WRAP),
                    mime,
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(title = "AI 图片导入", onBack = { nav.popBackStack() })

        Column(modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding)) {
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))

            if (settings.aiApiKey.isBlank()) {
                Text(
                    text = "尚未配置 AI 密钥。请到「设置 → AI 密钥」配置,支持 Gemini 及任意 OpenAI 兼容服务(DeepSeek、通义千问、Kimi、智谱 GLM 等)。",
                    style = YohakuType.copy13,
                    color = colors.error,
                )
                Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
                Text(
                    text = "去设置",
                    style = YohakuType.copy13,
                    color = colors.accent,
                    modifier = Modifier
                        .clickable { nav.navigate("settings") }
                        .padding(vertical = 4.dp),
                )
            } else {
                Text(
                    text = "拍摄或选择一张课表照片,AI 将识别课程与作息时间。",
                    style = YohakuType.label12,
                    color = colors.neutral7,
                )
                Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
                YohakuButton(
                    text = if (state is AiUiState.Loading) "识别中…" else "选择课表图片",
                    onClick = {
                        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    enabled = state !is AiUiState.Loading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            when (val s = state) {
                is AiUiState.Done -> {
                    Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
                    Text(
                        text = "识别到 ${s.count} 门课${if (s.timeApplied) ",已应用作息时间" else ""}",
                        style = YohakuType.copy13,
                        color = colors.neutral9,
                    )
                }
                is AiUiState.Error -> {
                    Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
                    Text(text = s.message, style = YohakuType.copy13, color = colors.error)
                }
                else -> {}
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
        }

        val courses = result?.courses ?: emptyList()
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = YohakuDimens.screenPadding,
            ),
        ) {
            items(courses, key = { it.id }) { course ->
                Row(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = Course.weekdaysShort(course),
                        style = YohakuType.timeMono,
                        color = colors.neutral7,
                        modifier = Modifier.width(44.dp),
                    )
                    Text(
                        text = Schedule.periodRange(startPeriod = course.startPeriod, endPeriod = course.endPeriod),
                        style = YohakuType.timeMono,
                        color = colors.neutral7,
                        modifier = Modifier.width(90.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = course.name, style = YohakuType.copy14, color = colors.neutral10)
                        val meta = listOf(course.teacher, course.location, weekText(course))
                            .filter { it.isNotBlank() }.joinToString(" · ")
                        if (meta.isNotEmpty()) {
                            Text(text = meta, style = YohakuType.label12, color = colors.neutral7)
                        }
                    }
                }
            }
        }

        if (courses.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(YohakuDimens.screenPadding),
            ) {
                YohakuButton(
                    text = "导入 ${courses.size} 门课",
                    onClick = { viewModel.importAll() },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun weekText(course: Course): String = course.weeksText()

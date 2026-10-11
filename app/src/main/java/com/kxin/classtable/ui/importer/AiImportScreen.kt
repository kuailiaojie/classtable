package com.kxin.classtable.ui.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.AiClient
import com.kxin.classtable.data.AiScheduleResult
import com.kxin.classtable.data.Analytics
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.Crash
import com.kxin.classtable.data.Perf
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.yohakuTouchTarget
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.weeksText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject

sealed interface AiUiState {
    data object Idle : AiUiState
    data object Loading : AiUiState
    data class Done(val count: Int, val timeApplied: Boolean) : AiUiState
    data class Error(val message: String) : AiUiState
}

@HiltViewModel
class AiImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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

    /**
     * 读取所选图片 → 下采样重编码 → 交给 AI 识别。
     *
     * 读文件与 Base64 编码都放在 IO 线程:一张相机原图有几十 MB,在主线程读会卡顿甚至 ANR;
     * 下采样后通常只剩几百 KB,既省流量也避免 OOM。
     */
    fun analyze(settings: AppSettings, uri: Uri) {
        viewModelScope.launch {
            _state.value = AiUiState.Loading
            val encoded = withContext(Dispatchers.IO) { runCatching { encodeImageForUpload(context, uri) } }
            val image = encoded.getOrElse { e ->
                _state.value = AiUiState.Error(e.message ?: "图片读取失败")
                Crash.recordException(e, "op" to "ai_image_read")
                return@launch
            }
            val outcome = Perf.trace("ai_recognize_duration") {
                AiClient.extractSchedule(
                    provider = settings.provider(),
                    apiKey = settings.aiApiKey,
                    baseUrl = settings.aiBaseUrl,
                    model = settings.aiModel,
                    imageBase64 = image.base64,
                    mimeType = image.mime,
                    weekCount = settings.semesterWeekCount,
                )
            }
            outcome
                .onSuccess { r ->
                    _result.value = r
                    r.periodTimes?.let { settingsRepository.setPeriodTimes(it) }
                    _state.value = AiUiState.Done(r.courses.size, r.periodTimes != null)
                    Analytics.log("ai_recognize_succeeded", "count" to r.courses.size)
                }
                .onFailure { e ->
                    _state.value = AiUiState.Error(e.message ?: "解析失败")
                    Analytics.log("ai_recognize_failed", "reason" to e.javaClass.simpleName)
                    Crash.recordException(e, "op" to "ai_recognize")
                }
        }
    }

    fun importAll() {
        viewModelScope.launch {
            courseRepository.importAll(_result.value?.courses ?: emptyList(), source = "ai")
            _imported.value = true
        }
    }
}

private const val MAX_IMAGE_DIM = 1600
private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024

private data class EncodedImage(val base64: String, val mime: String)

/** 读取图片并缩到最长边 ≤ [MAX_IMAGE_DIM] 再编码为 JPEG,返回 Base64 与 MIME。 */
private fun encodeImageForUpload(context: Context, uri: Uri): EncodedImage {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IOException("无法读取所选图片")
    if (bytes.size > MAX_IMAGE_BYTES) throw IOException("图片过大,请选择 20MB 以内的图片")

    // 先只读尺寸、算出采样率,避免把整张原图解码进内存。
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || longest <= 0) {
        // 无法解析尺寸(如部分 HEIC):退回原始字节,交给服务端判断
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        return EncodedImage(Base64.encodeToString(bytes, Base64.NO_WRAP), mime)
    }
    var sample = 1
    while (longest / sample > MAX_IMAGE_DIM) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw IOException("图片无法解码,请换一张图片")
    return try {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        EncodedImage(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), "image/jpeg")
    } finally {
        bitmap.recycle()
    }
}

/** AI 图片导入:拍摄/选择课表图片 → 配置的 AI 供应商识别(Gemini / OpenAI 兼容)→ 预览 → 导入。 */
@Composable
fun AiImportScreen(
    nav: NavHostController,
    viewModel: AiImportViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val imported by viewModel.imported.collectAsStateWithLifecycle()

    LaunchedEffect(imported) { if (imported) nav.popBackStack() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null && settings.aiApiKey.isNotBlank()) {
            viewModel.analyze(settings, uri)
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
                    text = "尚未配置图片识别。请到「设置 → 图片识别」配置,支持 Gemini 及任意 OpenAI 兼容服务(DeepSeek、通义千问、Kimi、智谱 GLM 等)。",
                    style = YohakuType.copy13,
                    color = colors.error,
                )
                Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
                Text(
                    text = "去设置",
                    style = YohakuType.copy13,
                    color = colors.accent,
                    modifier = Modifier
                        .yohakuTouchTarget()
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
                is AiUiState.Loading -> {
                    // 识别期间给一行提示:此前只有一个「识别中…」的按钮文案,列表区一片空白
                    Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
                    Text(text = "正在识别,请稍候…", style = YohakuType.copy13, color = colors.neutral7)
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
            if (courses.isEmpty() && state is AiUiState.Idle) {
                item {
                    Text(
                        text = "上传一张课表截图后会列出识别到的课程。",
                        style = YohakuType.label12,
                        color = colors.neutral7,
                    )
                }
            }
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

package com.kxin.classtable.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTextField
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.domain.model.AiProvider
import com.kxin.classtable.domain.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AiKeyViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    fun save(provider: String, key: String, baseUrl: String, model: String) = viewModelScope.launch {
        settingsRepository.setAiProvider(provider)
        settingsRepository.setAiKey(key)
        settingsRepository.setAiBaseUrl(baseUrl)
        settingsRepository.setAiModel(model)
        _saved.value = true
    }
}

/**
 * AI 密钥页(从设置页「导入与识别 → AI 密钥」进入)。
 * 密钥只存本机、不参与云端设置同步;服务商 / 地址 / 模型随设置一起同步。
 */
@Composable
fun AiKeyScreen(
    nav: NavHostController,
    viewModel: AiKeyViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    var provider by rememberSaveable { mutableStateOf(settings.aiProvider) }
    var key by rememberSaveable { mutableStateOf(settings.aiApiKey) }
    var baseUrl by rememberSaveable { mutableStateOf(settings.aiBaseUrl) }
    var model by rememberSaveable { mutableStateOf(settings.aiModel) }
    var hydrated by remember { mutableStateOf(false) }

    // settings 流先发一次占位值再发真实数据:等真实值到位后只灌一次,
    // 既不会用占位覆盖已填内容,也不会在用户编辑时被远端同步改回去。
    LaunchedEffect(settings) {
        if (!hydrated && settings != AppSettings()) {
            provider = settings.aiProvider
            key = settings.aiApiKey
            baseUrl = settings.aiBaseUrl
            model = settings.aiModel
            hydrated = true
        }
    }
    LaunchedEffect(saved) { if (saved) nav.popBackStack() }

    val selected = runCatching { AiProvider.valueOf(provider) }.getOrDefault(AiProvider.GEMINI)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(title = "AI 密钥", onBack = { nav.popBackStack() })

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = YohakuDimens.screenPadding),
        ) {
            Text(
                text = "用于 AI 图片识别课表。密钥只存本机,不上传也不参与云端设置同步。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            Text(text = "服务商", style = YohakuType.label12, color = colors.neutral7)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiProvider.entries.forEach { p ->
                    YohakuChip(
                        text = p.label,
                        selected = provider == p.name,
                        onClick = {
                            provider = p.name
                            // 换了服务商,地址与模型回到该家默认,避免把上一家的值带过去
                            baseUrl = ""
                            model = ""
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            YohakuTextField(
                value = key,
                onValueChange = { key = it },
                label = "API Key",
                placeholder = "sk-...",
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            if (selected == AiProvider.OPENAI_COMPAT) {
                YohakuTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = "Base URL",
                    placeholder = selected.defaultBaseUrl,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "常用:OpenAI https://api.openai.com/v1 · DeepSeek https://api.deepseek.com/v1 · " +
                        "通义千问 https://dashscope.aliyuncs.com/compatible-mode/v1 · Kimi https://api.moonshot.cn/v1 · " +
                        "智谱 https://open.bigmodel.cn/api/paas/v4",
                    style = YohakuType.label12,
                    color = colors.neutral7,
                )
                Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
            }

            YohakuTextField(
                value = model,
                onValueChange = { model = it },
                label = "模型",
                placeholder = selected.defaultModel,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "图片识别需支持视觉的模型,如 gemini-2.5-flash / gpt-4o / qwen-vl-plus / " +
                    "glm-4v-flash / moonshot-v1-8k-vision-preview。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(YohakuDimens.screenPadding),
        ) {
            YohakuButton(
                text = "保存",
                onClick = { viewModel.save(provider, key, baseUrl, model) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

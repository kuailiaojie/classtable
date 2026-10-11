package com.kxin.classtable.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kxin.classtable.data.AI_ASSISTANT_PROMPT
import com.kxin.classtable.data.AiAction
import com.kxin.classtable.data.AiChatMessage
import com.kxin.classtable.data.AiClient
import com.kxin.classtable.data.AiPlanner
import com.kxin.classtable.data.Analytics
import com.kxin.classtable.data.AgendaRepository
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.Crash
import com.kxin.classtable.data.Perf
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.parseAiChatReply
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** 助手页里的一条消息。用户消息 fromUser=true;助手消息可能带一串待确认动作。 */
data class AssistantMessage(
    val id: String = UUID.randomUUID().toString(),
    val fromUser: Boolean,
    val text: String,
    val actions: List<AiAction> = emptyList(),
    /** 动作已确认(或已取消);确认后不再允许再次落库。 */
    val actionsHandled: Boolean = false,
    val failed: Boolean = false,
)

/**
 * AI 助手:把用户一句话交给模型,拿回「回复 + 结构化动作」;动作**先预览、点确认才落库**。
 * 询问课表类问题不产出动作,直接显示回复。
 */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val courseRepository: CourseRepository,
    private val agendaRepository: AgendaRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<AssistantMessage>>(emptyList())
    val messages: StateFlow<List<AssistantMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun send(input: String) {
        val text = input.trim()
        if (text.isEmpty() || _busy.value) return
        _messages.value = _messages.value + AssistantMessage(fromUser = true, text = text)
        _busy.value = true

        viewModelScope.launch {
            val settings = settingsRepository.currentSettings()
            if (settings.aiApiKey.isBlank()) {
                _messages.value = _messages.value + AssistantMessage(
                    fromUser = false,
                    text = "还没有配置 AI 密钥。去「设置 → 图片识别」填一个即可(密钥只存本机)。",
                    failed = true,
                )
                _busy.value = false
                return@launch
            }

            val courses = courseRepository.observeAll().first()
            val agenda = agendaRepository.observeAll().first()
            val context = AiPlanner.buildContext(courses, agenda, settings)
            val systemPrompt = AI_ASSISTANT_PROMPT + "\n\n当前数据(供你换算日期/避开冲突):\n" + context

            val history = buildHistory()
            val result = Perf.trace("ai_assistant_duration") {
                AiClient.chat(
                    provider = settings.provider(),
                    apiKey = settings.aiApiKey,
                    baseUrl = settings.aiBaseUrl,
                    model = settings.aiModel,
                    systemPrompt = systemPrompt,
                    history = history,
                )
            }

            result
                .onSuccess { raw ->
                    val parsed = parseAiChatReply(raw)
                    _messages.value = _messages.value + AssistantMessage(
                        fromUser = false,
                        text = parsed.reply,
                        actions = parsed.actions,
                    )
                    Analytics.log(
                        "ai_assistant_replied",
                        "actions" to parsed.actions.size,
                    )
                }
                .onFailure { error ->
                    Crash.recordException(error, "op" to "ai_assistant")
                    Analytics.log("ai_assistant_failed")
                    _messages.value = _messages.value + AssistantMessage(
                        fromUser = false,
                        text = "调用失败:${error.message ?: error.javaClass.simpleName}",
                        failed = true,
                    )
                }
            _busy.value = false
        }
    }

    /** 确认某条助手消息里的动作:落库并标记已处理。 */
    fun confirm(messageId: String) {
        val message = _messages.value.firstOrNull { it.id == messageId } ?: return
        if (message.actionsHandled || message.actions.isEmpty()) return
        _messages.value = _messages.value.map {
            if (it.id == messageId) it.copy(actionsHandled = true) else it
        }

        viewModelScope.launch {
            val settings = settingsRepository.currentSettings()
            var courses = 0
            var events = 0
            message.actions.forEach { action ->
                when (action) {
                    is AiAction.CreateCourse -> runCatching {
                        courseRepository.save(AiPlanner.courseDraft(action, settings))
                    }.onSuccess { courses++ }
                        .onFailure { Crash.recordException(it, "op" to "ai_create_course") }

                    is AiAction.CreateAgenda -> runCatching {
                        AiPlanner.agendaDraft(action)?.let { agendaRepository.save(it) }
                    }.onSuccess { if (it != null) events++ }
                        .onFailure { Crash.recordException(it, "op" to "ai_create_agenda") }
                }
            }
            Analytics.log("ai_assistant_applied", "courses" to courses, "agenda" to events)
            _toast.value = buildString {
                append("已添加")
                if (courses > 0) append(" $courses 门课")
                if (events > 0) append(" $events 条日程")
                if (courses == 0 && events == 0) append(" 0 项(动作无法解析)")
            }
        }
    }

    fun dismiss(messageId: String) {
        _messages.value = _messages.value.map {
            if (it.id == messageId) it.copy(actionsHandled = true) else it
        }
    }

    fun clearToast() {
        _toast.value = null
    }

    /** 只带最近的对话文本做上下文,避免请求体无限膨胀。 */
    private fun buildHistory(): List<AiChatMessage> =
        _messages.value
            .takeLast(10)
            .map { AiChatMessage(role = if (it.fromUser) "user" else "assistant", content = it.text) }
}

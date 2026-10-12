package com.kxin.classtable.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kxin.classtable.data.AgentContext
import com.kxin.classtable.data.AgentToolResult
import com.kxin.classtable.data.AgentTools
import com.kxin.classtable.data.AiAction
import com.kxin.classtable.data.AiChatMessage
import com.kxin.classtable.data.AiChatReply
import com.kxin.classtable.data.AiChatTurn
import com.kxin.classtable.data.AiClient
import com.kxin.classtable.data.AiFunctionSpec
import com.kxin.classtable.data.AiFunctions
import com.kxin.classtable.data.AiPlanner
import com.kxin.classtable.data.AiPages
import com.kxin.classtable.data.AiSettingRegistry
import com.kxin.classtable.data.AiToolRequest
import com.kxin.classtable.data.Analytics
import com.kxin.classtable.data.AgendaRepository
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.Crash
import com.kxin.classtable.data.Perf
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.aiActionIssuesFeedback
import com.kxin.classtable.data.aiActionIssuesSummary
import com.kxin.classtable.data.aiAssistantPrompt
import com.kxin.classtable.data.parseAiChatReply
import com.kxin.classtable.data.parseToolArgs
import com.kxin.classtable.data.toolResultMessage
import com.kxin.classtable.data.validateAiActions
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.Course
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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
 * AI 助手:一句话建课 / 改课 / 删课 / 建日程 / 记偏好,或询问课表。
 *
 * 与「一次性问答」不同,这里跑一个**只读工具循环**:模型每轮要么请求一次读取(见 [AgentTools]),
 * 要么给出最终 `actions`。拿到 actions 后先做本地校验,再把具体问题喂回模型重出一份完整计划;
 * **用户确认前不落库** —— 模型幻觉不会直接改到课表。
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

    /** 现有课程:仅供预览卡解析「改 / 删」的目标课程,展示改前内容。 */
    val courses: StateFlow<List<Course>> = courseRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前设置:预览卡要给「改设置」显示改前的值。 */
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** 确认后要把用户带去的页面路由(由面板消费,再关面板 + 导航)。 */
    private val _pendingRoute = MutableStateFlow<String?>(null)
    val pendingRoute: StateFlow<String?> = _pendingRoute.asStateFlow()

    fun clearPendingRoute() {
        _pendingRoute.value = null
    }

    fun send(input: String) {
        val text = input.trim()
        if (text.isEmpty() || _busy.value) return
        _messages.value = _messages.value + AssistantMessage(fromUser = true, text = text)
        _busy.value = true

        viewModelScope.launch {
            val settings = settingsRepository.currentSettings()
            if (settings.aiApiKey.isBlank()) {
                replyFailure("还没有配置 AI。去「设置 → AI 设置」填一个即可(密钥只存本机)。")
                return@launch
            }

            val courses = courseRepository.observeAll().first()
            val agenda = agendaRepository.observeAll().first()
            val memory = settingsRepository.agentMemory.first()
            val ctx = AgentContext(settings, courses, agenda, memory)
            val systemPrompt = aiAssistantPrompt() + "\n\n" + AiPlanner.buildContext(settings, memory)

            val startedAt = System.currentTimeMillis()
            val history = buildHistory().toMutableList()
            val toolCache = HashMap<String, AgentToolResult>()
            // 原生 function calling 的只读工具(provider 不支持时由客户端退回纯文本,自动降级)。
            val functions = if (AiFunctions.NATIVE_READ_TOOLS) AiFunctions.readTools else emptyList()
            var rounds = 0
            var toolCalls = 0
            var final: AiChatReply? = null
            var finalRaw: String? = null
            var transcriptChars = 0

            while (rounds < MAX_ROUNDS) {
                rounds++
                val turn = chat(settings, systemPrompt, history, functions).getOrElse { error ->
                    Crash.recordException(error, "op" to "ai_assistant")
                    Analytics.log("ai_assistant_failed")
                    replyFailure("调用失败:${error.message ?: error.javaClass.simpleName}")
                    return@launch
                }
                transcriptChars += turn.text.length

                // 原生工具调用:执行后把结果回灌,继续下一轮。
                if (turn.toolCalls.isNotEmpty()) {
                    history += AiChatMessage(role = "assistant", content = turn.text, toolCalls = turn.toolCalls)
                    turn.toolCalls.forEach { call ->
                        toolCalls++
                        val (result, content) =
                            runTool(AiToolRequest(call.name, parseToolArgs(call.argumentsJson)), ctx, toolCache)
                        transcriptChars += content.length
                        history += AiChatMessage(
                            role = "tool",
                            content = content,
                            toolName = result.name,
                            toolCallId = call.id,
                        )
                    }
                    continue
                }

                val parsed = parseAiChatReply(turn.text)
                val legacyTool = parsed.tool
                if (legacyTool != null) {
                    // 老协议回退:provider 不支持原生工具时,模型会在 JSON 里给 tool 字段。
                    toolCalls++
                    val (result, content) = runTool(legacyTool, ctx, toolCache)
                    transcriptChars += content.length
                    history += AiChatMessage(role = "assistant", content = turn.text)
                    history += AiChatMessage(role = "user", content = toolResultMessage(result.name, content))
                    continue
                }
                final = parsed
                finalRaw = turn.text
                break
            }

            var parsed = final
            if (parsed == null) {
                replyFailure("这一轮没能得出结论,请再试一次或换个说法。")
                return@launch
            }

            // 本地校验 + 一次修回:把具体字段与原因喂回模型,重出一份完整计划。
            var issues = parsed.issues + validateAiActions(parsed.actions, settings, courses)
            if (issues.isNotEmpty() && parsed.rawActionCount > 0) {
                // 回放模型自己的原始输出再指出问题 —— 它得先「看见」自己写了什么,才改得对。
                val repairHistory = history +
                    AiChatMessage(role = "assistant", content = finalRaw ?: parsed.reply) +
                    AiChatMessage(role = "user", content = aiActionIssuesFeedback(issues))
                // 修回这一轮不带工具:要的是模型把计划改成合法 JSON,不是再去查一遍。
                val repairedTurn = chat(settings, systemPrompt, repairHistory, emptyList()).getOrNull()
                if (repairedTurn != null) {
                    val repaired = parseAiChatReply(repairedTurn.text)
                    parsed = repaired
                    issues = repaired.issues + validateAiActions(repaired.actions, settings, courses)
                }
            }

            val blocked = issues.isNotEmpty() && parsed.rawActionCount > 0
            val replyText = if (blocked) {
                parsed.reply + "\n\n**本次操作未通过校验,没有生成可确认的计划:**\n" + aiActionIssuesSummary(issues)
            } else {
                parsed.reply
            }
            _messages.value = _messages.value + AssistantMessage(
                fromUser = false,
                text = replyText,
                actions = if (blocked) emptyList() else parsed.actions,
            )

            if (!blocked && parsed.actions.isNotEmpty()) {
                Analytics.log("ai_assistant_replied", "actions" to parsed.actions.size)
            }
            // 轻量遥测:轮数 / 工具调用 / 动作数 / 字符数 / 延迟。不落库、不含课程正文。
            Analytics.log(
                "ai_agent_turn",
                "rounds" to rounds,
                "tools" to toolCalls,
                "actions" to parsed.actions.size,
                "chars" to transcriptChars,
                "latency_ms" to (System.currentTimeMillis() - startedAt),
            )
            _busy.value = false
        }
    }

    /** 一次文本对话(可带只读工具)。失败已上报,把原始错误交给调用方决定怎么说明。 */
    private suspend fun chat(
        settings: AppSettings,
        systemPrompt: String,
        history: List<AiChatMessage>,
        functions: List<AiFunctionSpec>,
    ): Result<AiChatTurn> = Perf.trace("ai_assistant_duration") {
        AiClient.chat(
            provider = settings.provider(),
            apiKey = settings.aiApiKey,
            baseUrl = settings.aiBaseUrl,
            model = settings.aiModel,
            systemPrompt = systemPrompt,
            history = history,
            functions = functions,
        )
    }

    /** 执行一次工具调用;同一轮里相同调用只跑一次(参数归一后作键)。 */
    private fun runTool(
        request: AiToolRequest,
        ctx: AgentContext,
        cache: MutableMap<String, AgentToolResult>,
    ): Pair<AgentToolResult, String> {
        val key = request.name.trim().uppercase() + "|" +
            request.args.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        val result = cache.getOrPut(key) { AgentTools.execute(request, ctx) }
        val content = if (result.ok) result.content else "（工具失败）${result.content}"
        return result to content
    }

    private fun replyFailure(text: String) {
        _messages.value = _messages.value + AssistantMessage(fromUser = false, text = text, failed = true)
        _busy.value = false
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
            var memories = 0
            var settingsChanged = 0
            var opened = 0
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

                    is AiAction.UpdateCourse -> runCatching {
                        applyCourseEdit(action.courseId, action, settings)
                    }.onSuccess { if (it) courses++ }
                        .onFailure { Crash.recordException(it, "op" to "ai_update_course") }

                    is AiAction.DeleteCourse -> runCatching {
                        applyCourseEdit(action.courseId, action, settings)
                    }.onSuccess { if (it) courses++ }
                        .onFailure { Crash.recordException(it, "op" to "ai_delete_course") }

                    is AiAction.Remember -> runCatching {
                        val current = settingsRepository.agentMemory.first()
                        settingsRepository.setAgentMemory(current + action.text)
                        memories++
                    }.onFailure { Crash.recordException(it, "op" to "ai_remember") }

                    is AiAction.SetSetting -> runCatching {
                        val entry = AiSettingRegistry.find(action.key) ?: return@runCatching
                        val value = AiSettingRegistry.normalize(entry, action.value) ?: return@runCatching
                        entry.apply(settingsRepository, value)
                        settingsChanged++
                    }.onFailure { Crash.recordException(it, "op" to "ai_set_setting") }

                    // 导航不改数据:只记下路由,由面板消费(关面板 + 跳转)。
                    is AiAction.OpenSettings -> runCatching {
                        AiPages.find(action.page)?.let {
                            _pendingRoute.value = it.route
                            opened++
                        }
                    }.onFailure { Crash.recordException(it, "op" to "ai_open_page") }
                }
            }
            Analytics.log(
                "ai_assistant_applied",
                "courses" to courses,
                "agenda" to events,
                "memory" to memories,
                "settings" to settingsChanged,
                "opened" to opened,
            )
            _toast.value = buildString {
                append("已处理")
                if (courses > 0) append(" $courses 门课")
                if (events > 0) append(" $events 条日程")
                if (memories > 0) append(" $memories 条记忆")
                if (settingsChanged > 0) append(" $settingsChanged 项设置")
                if (opened > 0) append(" 打开页面")
                if (courses == 0 && events == 0 && memories == 0 && settingsChanged == 0 && opened == 0) {
                    append(" 0 项(动作无法解析)")
                }
            }
        }
    }

    /** 把一次改 / 删课动作落到课程记录:命中返回 true;记录被拆空则删原记录。 */
    private suspend fun applyCourseEdit(courseId: String, action: AiAction, settings: AppSettings): Boolean {
        val existing = courseRepository.get(courseId) ?: return false
        val finalRecords = AiPlanner.applyCourseAction(existing, action, settings)
        if (finalRecords.none { it.id == existing.id }) courseRepository.delete(existing.id)
        finalRecords.forEach { courseRepository.save(it) }
        return true
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

    private companion object {
        /** 任务循环上限:给组合查询留出连续依赖的空间,又不至于无限读。 */
        const val MAX_ROUNDS = 6
    }
}

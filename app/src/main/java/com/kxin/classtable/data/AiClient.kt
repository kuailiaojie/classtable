package com.kxin.classtable.data

import com.kxin.classtable.domain.model.AiProvider

/**
 * AI 供应商分发器:按 AppSettings 中配置的供应商调用对应客户端。
 * 空配置项回退到该供应商的默认值(模型 / Base URL)。
 */
object AiClient {

    suspend fun extractSchedule(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String,
        model: String,
        imageBase64: String,
        mimeType: String,
        weekCount: Int = 0,
    ): Result<AiScheduleResult> = when (provider) {
        AiProvider.GEMINI -> GeminiClient.extractSchedule(
            apiKey = apiKey,
            model = model.ifBlank { AiProvider.GEMINI.defaultModel },
            imageBase64 = imageBase64,
            mimeType = mimeType,
            weekCount = weekCount,
        )
        AiProvider.OPENAI_COMPAT -> OpenAiClient.extractSchedule(
            apiKey = apiKey,
            baseUrl = baseUrl.ifBlank { AiProvider.OPENAI_COMPAT.defaultBaseUrl },
            model = model.ifBlank { AiProvider.OPENAI_COMPAT.defaultModel },
            imageBase64 = imageBase64,
            mimeType = mimeType,
            weekCount = weekCount,
        )
    }

    /**
     * 助手页的文本对话。返回这一轮的文本与可能携带的**原生函数调用**。
     *
     * [functions] 非空时按 provider 的 wire 形态下发 tools;空则退回「模型在 JSON 里给 tool 字段」
     * 的老协议 —— 两条路径共用同一份 [AiFunctions] 定义与 [AgentTools] 执行。
     */
    suspend fun chat(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        history: List<AiChatMessage>,
        functions: List<AiFunctionSpec> = emptyList(),
    ): Result<AiChatTurn> = when (provider) {
        AiProvider.GEMINI -> GeminiClient.chat(
            apiKey = apiKey,
            model = model.ifBlank { AiProvider.GEMINI.defaultModel },
            systemPrompt = systemPrompt,
            history = history,
            functions = functions,
        )
        AiProvider.OPENAI_COMPAT -> OpenAiClient.chat(
            apiKey = apiKey,
            baseUrl = baseUrl.ifBlank { AiProvider.OPENAI_COMPAT.defaultBaseUrl },
            model = model.ifBlank { AiProvider.OPENAI_COMPAT.defaultModel },
            systemPrompt = systemPrompt,
            history = history,
            functions = functions,
        )
    }
}

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
    ): Result<AiScheduleResult> = when (provider) {
        AiProvider.GEMINI -> GeminiClient.extractSchedule(
            apiKey = apiKey,
            model = model.ifBlank { AiProvider.GEMINI.defaultModel },
            imageBase64 = imageBase64,
            mimeType = mimeType,
        )
        AiProvider.OPENAI_COMPAT -> OpenAiClient.extractSchedule(
            apiKey = apiKey,
            baseUrl = baseUrl.ifBlank { AiProvider.OPENAI_COMPAT.defaultBaseUrl },
            model = model.ifBlank { AiProvider.OPENAI_COMPAT.defaultModel },
            imageBase64 = imageBase64,
            mimeType = mimeType,
        )
    }
}

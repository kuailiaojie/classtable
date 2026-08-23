package com.kxin.classtable.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容的视觉对话接口(chat/completions)。
 * 适用于:OpenAI、DeepSeek、通义千问(DashScope 兼容模式)、Kimi、智谱 GLM、
 * SiliconFlow 等任何提供 /v1/chat/completions 的服务;需模型支持图片输入。
 */
object OpenAiClient {

    suspend fun extractSchedule(
        apiKey: String,
        baseUrl: String,
        model: String,
        imageBase64: String,
        mimeType: String,
    ): Result<AiScheduleResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("model", model)
                .put("messages", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", JSONArray()
                            .put(JSONObject().put("type", "text").put("text", AI_SCHEDULE_PROMPT))
                            .put(JSONObject().put("type", "image_url").put(
                                "image_url",
                                JSONObject().put("url", "data:$mimeType;base64,$imageBase64"),
                            ))
                        )
                ))
                .put("max_tokens", 4096)
                .toString()

            val conn = URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.connectTimeout = 30_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            check(code in 200..299) { "AI 接口返回 $code:${text.take(200)}" }

            val raw = JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            parseAiResult(raw)
        }
    }
}

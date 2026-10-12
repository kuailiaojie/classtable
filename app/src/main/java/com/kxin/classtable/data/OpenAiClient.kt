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
        weekCount: Int = 0,
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
            parseAiResult(raw, weekCount)
        }
    }

    /**
     * 文本对话(助手页用):返回这一轮的文本,以及可能携带的**原生函数调用**。
     *
     * [functions] 非空时把只读工具以 `tools` 下发。有些第三方兼容端不认 `tools`,带工具请求失败
     * 会**自动退回纯文本**再试一次 —— 原生 function calling 与老的 JSON 协议两条路都走得通。
     */
    suspend fun chat(
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        history: List<AiChatMessage>,
        functions: List<AiFunctionSpec>,
    ): Result<AiChatTurn> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
            val text = try {
                postJson(endpoint, apiKey, buildChatBody(model, systemPrompt, history, functions))
            } catch (error: Exception) {
                if (functions.isEmpty()) throw error
                postJson(endpoint, apiKey, buildChatBody(model, systemPrompt, history, emptyList()))
            }
            parseChatTurn(text)
        }
    }

    private fun buildChatBody(
        model: String,
        systemPrompt: String,
        history: List<AiChatMessage>,
        functions: List<AiFunctionSpec>,
    ): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
        history.forEach { messages.put(toWireMessage(it)) }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", 1500)
            .put("temperature", 0.4)
        if (functions.isNotEmpty()) {
            body.put("tools", AiFunctions.openAiToolsJson(functions))
            body.put("tool_choice", "auto")
        }
        return body.toString()
    }

    /** 历史消息 → OpenAI wire 形态(助手带 tool_calls、工具结果带 tool_call_id)。 */
    private fun toWireMessage(message: AiChatMessage): JSONObject = when {
        message.role == "assistant" && message.toolCalls.isNotEmpty() -> {
            val obj = JSONObject().put("role", "assistant")
            obj.put("content", if (message.content.isBlank()) JSONObject.NULL else message.content)
            obj.put(
                "tool_calls",
                JSONArray().apply {
                    message.toolCalls.forEach { call ->
                        put(
                            JSONObject()
                                .put("id", call.id)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject().put("name", call.name).put("arguments", call.argumentsJson),
                                ),
                        )
                    }
                },
            )
            obj
        }

        message.role == "tool" -> JSONObject()
            .put("role", "tool")
            .put("content", message.content)
            .apply { message.toolCallId?.let { put("tool_call_id", it) } }

        else -> JSONObject().put("role", message.role).put("content", message.content)
    }

    private fun parseChatTurn(text: String): AiChatTurn {
        val message = JSONObject(text)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message")
        val content = if (message.isNull("content")) "" else message.optString("content")
        val calls = message.optJSONArray("tool_calls")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val call = arr.optJSONObject(i) ?: return@mapNotNull null
                val fn = call.optJSONObject("function") ?: return@mapNotNull null
                val name = fn.optString("name").trim()
                if (name.isBlank()) {
                    null
                } else {
                    AiToolCall(
                        id = call.optString("id").ifBlank { name },
                        name = name,
                        argumentsJson = fn.optString("arguments").ifBlank { "{}" },
                    )
                }
            }
        }.orEmpty()
        return AiChatTurn(content, calls)
    }

    private fun postJson(endpoint: String, apiKey: String, body: String): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
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
        return text
    }
}

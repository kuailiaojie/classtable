package com.kxin.classtable.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 调用 Gemini 视觉模型解析课表图片(用户自备 API Key,仅存本机)。 */
object GeminiClient {

    suspend fun extractSchedule(
        apiKey: String,
        model: String,
        imageBase64: String,
        mimeType: String,
        weekCount: Int = 0,
    ): Result<AiScheduleResult> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint =
                "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

            val body = JSONObject()
                .put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray()
                        .put(JSONObject().put("text", AI_SCHEDULE_PROMPT))
                        .put(JSONObject().put("inline_data", JSONObject()
                            .put("mime_type", mimeType)
                            .put("data", imageBase64)))
                    )
                ))
                .toString()

            val conn = URL("$endpoint?key=$apiKey").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            check(code in 200..299) { "Gemini 接口返回 $code:${text.take(200)}" }

            val content = JSONObject(text)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content")
            val raw = content.getJSONArray("parts").getString(0)
            parseAiResult(raw, weekCount)
        }
    }

    /**
     * 文本对话(助手页用):返回这一轮的文本与可能携带的**原生函数调用**。
     *
     * [functions] 非空时下发 `tools.functionDeclarations`;此时不再设 `responseMimeType=application/json`
     * (JSON 模式与 function calling 会打架)。请求失败会退回纯文本再试一次。
     */
    suspend fun chat(
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<AiChatMessage>,
        functions: List<AiFunctionSpec>,
    ): Result<AiChatTurn> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint =
                "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val text = try {
                postJson(endpoint, buildChatBody(systemPrompt, history, functions))
            } catch (error: Exception) {
                if (functions.isEmpty()) throw error
                postJson(endpoint, buildChatBody(systemPrompt, history, emptyList()))
            }
            parseChatTurn(text)
        }
    }

    private fun buildChatBody(
        systemPrompt: String,
        history: List<AiChatMessage>,
        functions: List<AiFunctionSpec>,
    ): String {
        val contents = JSONArray()
        history.forEach { contents.put(toWireContent(it)) }
        val generationConfig = JSONObject().put("temperature", 0.4)
        // JSON 模式与 function calling 不能同时用:带工具时让模型自由输出。
        if (functions.isEmpty()) generationConfig.put("responseMimeType", "application/json")

        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))),
            )
            .put("contents", contents)
            .put("generationConfig", generationConfig)
        if (functions.isNotEmpty()) body.put("tools", AiFunctions.geminiToolsJson(functions))
        return body.toString()
    }

    /** 历史消息 → Gemini wire 形态(助手带 functionCall、工具结果带回 functionResponse)。 */
    private fun toWireContent(message: AiChatMessage): JSONObject = when {
        message.role == "assistant" && message.toolCalls.isNotEmpty() -> JSONObject()
            .put("role", "model")
            .put(
                "parts",
                JSONArray().apply {
                    if (message.content.isNotBlank()) put(JSONObject().put("text", message.content))
                    message.toolCalls.forEach { call ->
                        put(
                            JSONObject().put(
                                "functionCall",
                                JSONObject()
                                    .put("name", call.name)
                                    .put("args", runCatching { JSONObject(call.argumentsJson) }.getOrDefault(JSONObject())),
                            ),
                        )
                    }
                },
            )

        message.role == "tool" -> JSONObject()
            .put("role", "user")
            .put(
                "parts",
                JSONArray().put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject()
                            .put("name", message.toolName ?: "tool")
                            .put("response", JSONObject().put("result", message.content)),
                    ),
                ),
            )

        else -> JSONObject()
            .put("role", if (message.role == "assistant") "model" else "user")
            .put("parts", JSONArray().put(JSONObject().put("text", message.content)))
    }

    private fun parseChatTurn(text: String): AiChatTurn {
        val parts = JSONObject(text)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content")
            .optJSONArray("parts") ?: JSONArray()
        val sb = StringBuilder()
        val calls = mutableListOf<AiToolCall>()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            part.optString("text").takeIf { it.isNotBlank() }?.let { sb.append(it) }
            part.optJSONObject("functionCall")?.let { fc ->
                val name = fc.optString("name").trim()
                if (name.isNotBlank()) {
                    calls += AiToolCall(
                        id = name,
                        name = name,
                        argumentsJson = fc.optJSONObject("args")?.toString() ?: "{}",
                    )
                }
            }
        }
        return AiChatTurn(sb.toString(), calls)
    }

    private fun postJson(endpoint: String, body: String): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        check(code in 200..299) { "Gemini 接口返回 $code:${text.take(200)}" }
        return text
    }
}

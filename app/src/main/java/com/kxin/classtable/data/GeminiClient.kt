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

    /** 文本对话(助手页用):返回模型原始文本(约定里是一段 JSON)。 */
    suspend fun chat(
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<AiChatMessage>,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val contents = JSONArray()
            history.forEach { m ->
                contents.put(
                    JSONObject()
                        .put("role", if (m.role == "assistant") "model" else "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", m.content))),
                )
            }
            val body = JSONObject()
                .put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))),
                )
                .put("contents", contents)
                .put(
                    "generationConfig",
                    JSONObject().put("temperature", 0.4).put("responseMimeType", "application/json"),
                )
                .toString()

            val endpoint =
                "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
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

            JSONObject(text)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts").getString(0)
        }
    }
}

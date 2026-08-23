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
            parseAiResult(raw)
        }
    }
}

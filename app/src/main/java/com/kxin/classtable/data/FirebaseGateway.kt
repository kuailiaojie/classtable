package com.kxin.classtable.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase REST 网关:所有 Auth / Firestore 请求经自建 Netlify 反代转发,
 * 客户端不再直连 Google 域名 → 中国大陆可正常登录与同步。
 * 网络栈用 HttpURLConnection(与 GeminiClient 同风格),零第三方网络库。
 *
 * 错误约定:网络问题(超时/断连)抛 IOException 子类(调用方可重试);
 * 业务错误(Firebase 错误码)抛 [FirebaseApiException],message 已映射为中文。
 */
@Singleton
class FirebaseGateway @Inject constructor() {

    /** 反代入口。部署 Netlify 后把 YOUR-SITE 换成你的站点名(建议绑自有域名)。 */
    val baseUrl: String = "https://YOUR-SITE.netlify.app/.netlify/functions/proxy"

    /** Firebase Web API key(google-services.json → api_key.current_key;公开值,安全靠规则 + HTTPS)。 */
    val apiKey: String = "AIzaSyBxhhRE5gDw8_jUwghAWc7oC83MOGf9rPY"

    /** Firebase 项目 ID(google-services.json → project_info.project_id)。 */
    val projectId: String = "classtable-4a7d0"

    private val firebaseErrors = mapOf(
        "EMAIL_EXISTS" to "该邮箱已注册,请直接登录",
        "EMAIL_NOT_FOUND" to "邮箱未注册",
        "INVALID_LOGIN_CREDENTIALS" to "邮箱或密码错误",
        "INVALID_PASSWORD" to "密码错误",
        "INVALID_EMAIL" to "邮箱格式不正确",
        "WEAK_PASSWORD" to "密码过弱(至少 6 位)",
        "USER_DISABLED" to "账号已被禁用",
        "TOO_MANY_ATTEMPTS_TRY_LATER" to "尝试次数过多,请稍后再试",
        "OPERATION_NOT_ALLOWED" to "该操作未启用",
        "PASSWORD_RESET_REQUIRED" to "需要重置密码",
        "EXPIRED_OOB_CODE" to "链接已过期,请重新发送",
        "INVALID_OOB_CODE" to "链接无效",
        "UNAUTHENTICATED" to "登录状态已失效,请重新登录",
        "PERMISSION_DENIED" to "无权限执行该操作",
        "NOT_FOUND" to "数据不存在",
    )

    /** Auth REST 调用:POST {base}/auth/v1/{method}?key=... 。 */
    suspend fun auth(method: String, body: JSONObject): JSONObject =
        request("$baseUrl/auth/v1/$method?key=$apiKey", "POST", body.toString(), bearer = null)

    /** Firestore REST 调用:path 为 /v1/ 之后的部分(如 documents/users/x/courses),带 ID token。 */
    suspend fun firestore(path: String, method: String, body: JSONObject?, idToken: String): JSONObject =
        request("$baseUrl/firestore/$path", method, body?.toString(), bearer = idToken)

    private suspend fun request(
        url: String,
        method: String,
        body: String?,
        bearer: String?,
    ): JSONObject = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Content-Type", "application/json")
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw parseError(text, status)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseError(responseBody: String, status: Int): Exception {
        val code = runCatching {
            JSONObject(responseBody).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrNull().orEmpty()
        val mapped = firebaseErrors[code]
        return if (mapped != null) {
            FirebaseApiException(code, mapped)
        } else if (code.isNotBlank()) {
            FirebaseApiException(code, code)
        } else {
            IOException("请求失败(HTTP $status)")
        }
    }
}

/** Firebase 业务错误:message 已映射为中文,调用方不应重试。 */
class FirebaseApiException(val code: String, message: String) : Exception(message)

/** URL 路径段编码(Firestore 文档 id 等)。 */
fun encodeSegment(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

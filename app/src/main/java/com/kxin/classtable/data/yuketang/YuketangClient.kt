package com.kxin.classtable.data.yuketang

import android.content.Context
import android.util.Log
import com.kxin.classtable.data.WebUserAgent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** 业务错误(errcode 非 0 / HTTP 异常状态)。 */
open class YuketangException(val code: Int, message: String) : IOException(message)

/** 登录态失效:需要重新登录。后台任务遇到它必须静默,UI 遇到它提示重新登录。 */
class NotLoggedInException : YuketangException(-1, "雨课堂登录已失效,请重新登录")

/** 候选端点不存在(404):换下一个候选继续试。 */
class YuketangNotFoundException(message: String) : YuketangException(404, message)

/**
 * 长江雨课堂只读客户端(课程列表 / 课程公告)。
 *
 * 只用参考文档里已知的端点形状 + 项目既有写法(HttpURLConnection + org.json),
 * 不引入新依赖。Cookie 与自定义头按文档约定携带。
 *
 * **公告端点**:`yuketang-api.md` 未收录公告接口。这里按社区常见的几处 v2/v3 路径
 * **依次探测**,第一个能返回业务成功的路径会被缓存下来(每个进程只探测一次);
 * 真实路径以登录页的抓包探针(tag `YuketangProbe`)为准,抓到后把 [ANNOUNCEMENT_PATHS]
 * 收敛成一条即可。解析写成宽容式:结构不认识时降级为空列表,不抛异常。
 */
@Singleton
class YuketangClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionStore: YuketangSessionStore,
) {
    @Volatile
    private var resolvedAnnouncementPath: String? = null

    private val desktopUa: String by lazy { WebUserAgent.desktop(context) }

    /**
     * 登录态是否有效。
     *
     * 语义要分清楚:`false` 表示**服务端明确说没登录**(可以据此清掉会话);
     * 网络不通时抛 [IOException],调用方按「暂时判断不了」处理,不能顺手把会话删了。
     */
    suspend fun verify(): Boolean = try {
        courses()
        true
    } catch (e: NotLoggedInException) {
        false
    }

    /** 课程班级列表。未登录/失效抛 [NotLoggedInException]。 */
    suspend fun courses(): List<YuketangCourse> {
        val json = request(COURSES_PATH, query = "identity=2")
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        return (0 until list.length()).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            val classroomId = item.optString("classroom_id").takeIf { it.isNotBlank() }
                ?: item.optString("classroomId").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            YuketangCourse(
                classroomId = classroomId,
                name = item.optString("name"),
                courseName = item.optJSONObject("course")?.optString("name").orEmpty(),
                teacherName = item.optJSONObject("teacher")?.optString("name").orEmpty(),
            )
        }
    }

    /** 某课程班级的公告(最新在前)。 */
    suspend fun announcements(classroomId: String): List<YuketangAnnouncement> {
        resolvedAnnouncementPath?.let { path ->
            return parseAnnouncements(request(expand(path, classroomId), classroomId), classroomId)
        }
        var lastFailure: YuketangException? = null
        for (path in ANNOUNCEMENT_PATHS) {
            try {
                val list = parseAnnouncements(request(expand(path, classroomId), classroomId), classroomId)
                resolvedAnnouncementPath = path
                return list
            } catch (e: NotLoggedInException) {
                throw e
            } catch (e: YuketangNotFoundException) {
                lastFailure = e
            } catch (e: YuketangException) {
                lastFailure = e
            }
            // IOException(网络不通)不换端点:不是端点的问题。
        }
        Log.w(TAG, "公告端点均不可用,最后一次失败: ${lastFailure?.message}")
        return emptyList()
    }

    private fun expand(path: String, classroomId: String): String =
        path.replace("{classroomId}", encodePath(classroomId))

    // ---- 请求 ----

    private suspend fun request(path: String, query: String? = null, classroomId: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val session = sessionStore.read() ?: throw NotLoggedInException()
            val origin = session.origin.ifBlank { YuketangSession.DEFAULT_ORIGIN }
            val url = buildString {
                append(origin).append(path)
                if (!query.isNullOrBlank()) append('?').append(query)
            }
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Cookie", session.cookieHeader)
                if (session.csrfToken.isNotBlank()) conn.setRequestProperty("X-CSRFToken", session.csrfToken)
                if (session.uvId.isNotBlank()) conn.setRequestProperty("uv-id", session.uvId)
                if (session.universityId.isNotBlank()) {
                    conn.setRequestProperty("university-id", session.universityId)
                }
                conn.setRequestProperty("xtbz", session.xtbz.ifBlank { YuketangSession.DEFAULT_XTBZ })
                conn.setRequestProperty("xt-agent", "web")
                conn.setRequestProperty("x-client", "web")
                conn.setRequestProperty("User-Agent", desktopUa)
                conn.setRequestProperty("Referer", referer(origin, classroomId))
                if (classroomId != null) conn.setRequestProperty("classroom-id", classroomId)
                conn.setRequestProperty("Accept-Encoding", "identity")

                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

                when {
                    status == 401 || status == 403 -> throw NotLoggedInException()
                    status == 404 -> throw YuketangNotFoundException("接口不存在:$path")
                    status !in 200..299 -> throw YuketangException(status, "雨课堂返回 HTTP $status")
                }
                val json = runCatching { JSONObject(text) }
                    .getOrElse { throw YuketangException(-1, "雨课堂响应不是 JSON") }
                val errcode = json.optInt("errcode", json.optInt("code", 0))
                if (errcode != 0) {
                    val message = json.optString("errmsg").ifBlank { json.optString("msg") }
                    if (looksLikeNotLoggedIn(errcode, message)) throw NotLoggedInException()
                    throw YuketangException(errcode, message.ifBlank { "雨课堂返回错误码 $errcode" })
                }
                json
            } finally {
                conn.disconnect()
            }
        }

    private fun referer(origin: String, classroomId: String?): String =
        if (classroomId.isNullOrBlank()) "$origin/v2/web/index" else "$origin/v2/web/studentLog/$classroomId"

    private fun looksLikeNotLoggedIn(errcode: Int, message: String): Boolean =
        errcode in NOT_LOGGED_IN_CODES ||
            message.contains("未登录") ||
            message.contains("请先登录") ||
            message.contains("重新登录")

    // ---- 解析(宽容) ----

    private fun parseAnnouncements(json: JSONObject, classroomId: String): List<YuketangAnnouncement> {
        val array = extractArray(json) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val title = firstString(item, "title", "name", "subject")
            val content = firstString(item, "content", "body", "text", "description", "summary")
            if (title.isBlank() && content.isBlank()) return@mapNotNull null
            val createdAt = firstTime(
                item,
                "created_at", "create_time", "createdAt", "publish_time", "published_at",
                "timestamp", "created", "time", "date",
            )
            val rawId = firstString(item, "id", "announcement_id", "notice_id", "pk")
            val id = rawId.ifBlank { "$classroomId:$createdAt:${title.hashCode()}" }
            YuketangAnnouncement(
                id = id,
                classroomId = classroomId,
                title = title.ifBlank { content.take(40) },
                content = content,
                createdAtMillis = createdAt,
                publisher = firstString(item, "publisher", "author", "teacher_name", "sender")
                    .ifBlank { item.optJSONObject("teacher")?.optString("name").orEmpty() },
            )
        }
    }

    /** 公告列表可能藏在不同键下(端点未定,结构也可能变)。 */
    private fun extractArray(json: JSONObject): JSONArray? {
        val candidates = listOf("list", "announcements", "notices", "records", "items", "data", "results")
        for (key in candidates) {
            when (val value = json.opt(key)) {
                is JSONArray -> return value
                is JSONObject -> {
                    // 嵌套一层:`data: { list: [...] }`
                    for (inner in candidates) {
                        (value.opt(inner) as? JSONArray)?.let { return it }
                    }
                }
                else -> Unit
            }
        }
        return null
    }

    private fun firstString(json: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = json.optString(key)
            if (value.isNotBlank()) return value
        }
        return ""
    }

    /** 时间字段常见三种形态:秒/毫秒时间戳、ISO 字符串、"yyyy-MM-dd HH:mm(:ss)"。 */
    private fun firstTime(json: JSONObject, vararg keys: String): Long {
        for (key in keys) {
            if (!json.has(key) || json.isNull(key)) continue
            val parsed = when (val value = json.opt(key)) {
                is Number -> normalizeEpoch(value.toLong())
                is String -> parseTimeText(value)
                else -> 0L
            }
            if (parsed > 0L) return parsed
        }
        return 0L
    }

    private fun normalizeEpoch(raw: Long): Long = when {
        raw <= 0L -> 0L
        // 10 位以内按秒算(雨课堂时间戳为秒级)
        raw < 100_000_000_000L -> raw * 1000L
        else -> raw
    }

    private fun parseTimeText(text: String): Long {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0L
        trimmed.toLongOrNull()?.let { return normalizeEpoch(it) }
        ISO_FORMATS.forEach { pattern ->
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.CHINA).parse(trimmed.replace('T', ' ').take(pattern.length + 4))
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return 0L
    }

    private fun encodePath(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    companion object {
        private const val TAG = "YuketangClient"
        private const val COURSES_PATH = "/v2/api/web/courses/list"

        /**
         * 公告端点候选(按可能性排序)。抓到真实端点后只留第一条。
         * `{classroomId}` 会被替换成班级 id。
         */
        val ANNOUNCEMENT_PATHS = listOf(
            "/api/v3/classroom/{classroomId}/announcement",
            "/v2/api/web/classroom/{classroomId}/announcement",
            "/v2/api/web/classrooms/{classroomId}/announcement",
            "/api/v3/classroom/{classroomId}/announcements",
        )

        private val NOT_LOGGED_IN_CODES = setOf(1001, 1002, 40001)

        private val ISO_FORMATS = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm",
            "yyyy/MM/dd",
        )
    }
}

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

/** 404:端点不存在(接口漂移时留个明确信号,而不是当成业务错误)。 */
class YuketangNotFoundException(message: String) : YuketangException(404, message)

/**
 * 长江雨课堂只读客户端(课程列表 / 课程公告)。
 *
 * 只用真机抓包确认过的端点 + 项目既有写法(HttpURLConnection + org.json),不引入新依赖。
 * Cookie 与自定义头按参考文档约定携带(`uv_id` / `university_id` 一律从 Cookie 里取实际值,
 * 不硬编码 —— 不同学校部署的值不一样)。
 */
@Singleton
class YuketangClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionStore: YuketangSessionStore,
) {
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

    /**
     * 某课程班级的公告(最新在前)。
     *
     * 端点是**真机抓包确认过的**(长江雨课堂网页版 → 课程 → 「公告」标签页):
     * `GET /v/discussion/v2/announcements/?content=&cid={cid}&limit=&offset=&type=9`
     *
     * 为什么在 `/v/discussion/` 而不是 `/v2/api/web/`:雨课堂的「公告」在服务端就是
     * `topic_type == 9` 的**讨论主题**,所以跟讨论区共用这一组接口。
     *
     * @param overridePath 设置里手填的接口路径(「高级」);非空则替换内置路径 —— 接口漂移时的逃生口。
     * @throws YuketangException 服务端明确报错时抛出;网络不通抛 IOException(由调用方区分处理)。
     */
    suspend fun announcements(
        classroomId: String,
        overridePath: String? = null,
    ): List<YuketangAnnouncement> {
        val path = expand(
            overridePath?.takeIf { it.isNotBlank() }
                ?.also { Log.i(TAG, "使用设置里指定的公告接口:$it") }
                ?: ANNOUNCEMENT_PATH,
            classroomId,
        )

        val collected = mutableListOf<YuketangAnnouncement>()
        var offset = 0
        // 翻页有上限:公告是「看最新几条」的场景,没必要把历史全捞回来(也少给对方服务器压力)。
        repeat(ANNOUNCEMENT_MAX_PAGES) { page ->
            val query = "content=&cid=" + encodePath(classroomId) +
                "&limit=" + ANNOUNCEMENT_PAGE_SIZE +
                "&offset=" + offset +
                "&type=" + ANNOUNCEMENT_TOPIC_TYPE
            val json = request(path, query = query, classroomId = classroomId, logBody = page == 0)
            val pageItems = parseAnnouncements(json, classroomId)
            collected += pageItems
            offset += ANNOUNCEMENT_PAGE_SIZE
            // 不满一页 = 到底了;拿满再看是否已达总数
            if (pageItems.size < ANNOUNCEMENT_PAGE_SIZE) return collected
            val total = json.optJSONObject("data")?.optInt("count", -1) ?: -1
            if (total in 0..offset) return collected
        }
        Log.i(TAG, "公告翻页到上限(${ANNOUNCEMENT_MAX_PAGES} 页),已取 ${collected.size} 条")
        return collected
    }

    private fun expand(path: String, classroomId: String): String =
        path.replace("{classroomId}", encodePath(classroomId))

    // ---- 请求 ----

    private suspend fun request(
        path: String,
        query: String? = null,
        classroomId: String? = null,
        logBody: Boolean = false,
    ): JSONObject =
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
                // 三套错误约定都要认:`errcode`(v2 web)、`code`(discussion 组)、`{success:false, error_code}`
                val code = json.optInt("errcode", json.optInt("code", json.optInt("error_code", 0)))
                if (code != 0 || !json.optBoolean("success", true)) {
                    val message = json.optString("errmsg").ifBlank { json.optString("msg") }
                    if (looksLikeNotLoggedIn(code, message)) throw NotLoggedInException()
                    throw YuketangException(code, message.ifBlank { "雨课堂返回错误(码 $code)" })
                }
                // 公告响应片段留痕:结构若再变,这是唯一能据以修解析的线索。
                if (logBody) Log.i(TAG, "GET $url → ${text.replace('\n', ' ').take(RESPONSE_SNIPPET)}")
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

    // ---- 解析 ----

    /**
     * 解析 `data.results[]`(抓包确认的结构,2026-09)。
     *
     * 一条公告形如:
     * ```json
     * { "id": 9149234, "topic_type": 9, "topic_name": "听力自主任务1",
     *   "content": { "text": "微信小程序:听力随身练", "app_text": "…", "upload_images": ["…"] },
     *   "publish_time": 1790172932000, "create_time": "2026-09-23T14:15:32.937881Z",
     *   "user_info": { "name": "徐芳" }, "publisher_name": null }
     * ```
     * 时间优先取 `publish_time`(epoch 毫秒);它缺失时才退回 `create_time`(ISO8601)。
     */
    private fun parseAnnouncements(json: JSONObject, classroomId: String): List<YuketangAnnouncement> {
        val results = json.optJSONObject("data")?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("topic_name").trim()
            val body = item.optJSONObject("content")?.optString("text").orEmpty().trim()
            if (title.isBlank() && body.isBlank()) return@mapNotNull null
            val createdAt = item.optLong("publish_time", 0L).let { published ->
                if (published > 0L) normalizeEpoch(published) else parseTimeText(item.optString("create_time"))
            }
            YuketangAnnouncement(
                // id 缺失时退化成一个稳定可复现的键,便于去重比较
                id = item.optString("id").takeIf { it.isNotBlank() }
                    ?: "$classroomId:$createdAt:${title.hashCode()}",
                classroomId = classroomId,
                title = title.ifBlank { body.take(40) },
                content = body,
                createdAtMillis = createdAt,
                publisher = item.optJSONObject("user_info")?.optString("name").orEmpty()
                    .ifBlank { item.optString("publisher_name") },
            )
        }
    }

    private fun normalizeEpoch(raw: Long): Long = when {
        raw <= 0L -> 0L
        // 10 位以内按秒算,否则按毫秒(雨课堂两种都出现过)
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
         * 公告列表(2026-09 抓包确认,长江雨课堂网页版 → 课程 → 「公告」标签页)。
         *
         * 雨课堂的「公告」在服务端就是 `topic_type == 9` 的讨论主题,所以这组接口在
         * `/v/discussion/` 下、错误约定是 `code`/`success` 而不是 `errcode`。
         */
        const val ANNOUNCEMENT_PATH = "/v/discussion/v2/announcements/"

        /** `type=9` = 公告(讨论区其它类型是别的数字)。 */
        private const val ANNOUNCEMENT_TOPIC_TYPE = 9
        private const val ANNOUNCEMENT_PAGE_SIZE = 30
        private const val ANNOUNCEMENT_MAX_PAGES = 3

        private val NOT_LOGGED_IN_CODES = setOf(1001, 1002, 40001)

        /** logcat 里保留的响应片段长度(排查公告结构用)。 */
        private const val RESPONSE_SNIPPET = 400

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

package com.kxin.classtable.data.yuketang

/**
 * 雨课堂登录会话:由 WebView 的 CookieManager 读出的 Cookie 解析而来。
 *
 * `sessionid` 是 **HttpOnly** Cookie,网页里的 `document.cookie` 读不到,只能由原生
 * `CookieManager.getCookie(url)` 取;因此这里额外保存**原始 Cookie 串**并原样回传,
 * 免得漏掉 `django_language` 之类的辅助项。
 */
data class YuketangSession(
    val sessionId: String,
    val csrfToken: String = "",
    val uvId: String = "",
    val universityId: String = "",
    val xtbz: String = "ykt",
    /** 站点 origin(如 `https://changjiang.yuketang.cn`):各校部署域名可能不同,登录时记下来。 */
    val origin: String = "https://changjiang.yuketang.cn",
    val rawCookie: String = "",
) {
    /** 发给服务端的 Cookie 头:优先原样回传,缺原始串时按字段拼回来。 */
    val cookieHeader: String
        get() = if (rawCookie.isNotBlank()) {
            rawCookie
        } else {
            buildString {
                append("sessionid=").append(sessionId)
                if (csrfToken.isNotBlank()) append("; csrftoken=").append(csrfToken)
                if (uvId.isNotBlank()) append("; uv_id=").append(uvId)
                if (universityId.isNotBlank()) append("; university_id=").append(universityId)
                append("; xtbz=").append(xtbz.ifBlank { DEFAULT_XTBZ })
            }
        }

    companion object {
        const val DEFAULT_ORIGIN = "https://changjiang.yuketang.cn"
        const val DEFAULT_XTBZ = "ykt"

        /** 解析 Cookie 头;**没有 sessionid 一律视为未登录**。 */
        fun fromCookie(cookie: String?, origin: String): YuketangSession? {
            val raw = cookie?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val values = parse(raw)
            val sessionId = values["sessionid"]?.takeIf { it.isNotBlank() } ?: return null
            return YuketangSession(
                sessionId = sessionId,
                csrfToken = values["csrftoken"].orEmpty(),
                uvId = values["uv_id"].orEmpty(),
                universityId = values["university_id"].orEmpty(),
                xtbz = values["xtbz"].orEmpty().ifBlank { DEFAULT_XTBZ },
                origin = origin,
                rawCookie = raw,
            )
        }

        private fun parse(cookie: String): Map<String, String> = cookie
            .split(';')
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
            }
            .toMap()
    }
}

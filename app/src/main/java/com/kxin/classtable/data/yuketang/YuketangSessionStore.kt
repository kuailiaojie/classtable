package com.kxin.classtable.data.yuketang

import android.content.Context
import com.kxin.classtable.data.Crash
import com.kxin.classtable.data.SecurePrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 雨课堂会话的加密持久化([SecurePrefs]),与云账号会话分开一个文件。
 * 登录态约两周失效:失效后由 [YuketangClient] 判定并提示重新登录。
 *
 * 旧版 EncryptedSharedPreferences 的数据不迁移:换加密格式后读不出来,退化为未登录即可。
 */
@Singleton
class YuketangSessionStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = SecurePrefs(context, FILE)

    init {
        // 清掉旧的 EncryptedSharedPreferences 文件(内容已不可读)。
        runCatching { context.deleteSharedPreferences(LEGACY_FILE) }
    }

    private val state = MutableStateFlow(readFromPrefs())

    /**
     * 当前会话流:登录/登出后设置页与详情页要立刻跟着变,所以除了 [read] 之外还提供这条
     * 可观察的流(单例,全应用共享同一份)。
     */
    val session: StateFlow<YuketangSession?> = state.asStateFlow()

    fun read(): YuketangSession? = state.value

    /** 传 null 表示登出。 */
    fun write(session: YuketangSession?) {
        runCatching {
            prefs.clear()
            if (session != null) {
                prefs.putString(KEY_SESSION_ID, session.sessionId)
                prefs.putString(KEY_CSRF, session.csrfToken)
                prefs.putString(KEY_UV_ID, session.uvId)
                prefs.putString(KEY_UNIVERSITY_ID, session.universityId)
                prefs.putString(KEY_XTBZ, session.xtbz)
                prefs.putString(KEY_ORIGIN, session.origin)
                prefs.putString(KEY_RAW_COOKIE, session.rawCookie)
            }
        }.onFailure { Crash.recordException(it, "op" to "yuketang_session_write") }
        state.value = session
    }

    private fun readFromPrefs(): YuketangSession? = runCatching {
        val sessionId = prefs.getString(KEY_SESSION_ID) ?: return null
        YuketangSession(
            sessionId = sessionId,
            csrfToken = prefs.getString(KEY_CSRF).orEmpty(),
            uvId = prefs.getString(KEY_UV_ID).orEmpty(),
            universityId = prefs.getString(KEY_UNIVERSITY_ID).orEmpty(),
            xtbz = prefs.getString(KEY_XTBZ).orEmpty()
                .ifBlank { YuketangSession.DEFAULT_XTBZ },
            origin = prefs.getString(KEY_ORIGIN).orEmpty()
                .ifBlank { YuketangSession.DEFAULT_ORIGIN },
            rawCookie = prefs.getString(KEY_RAW_COOKIE).orEmpty(),
        )
    }.getOrElse { e ->
        // 读取失败时清掉重来,退化为未登录。这里在构造期被调用(→ 单例初始化),
        // 不捕获的话异常会从 Hilt 构造链冒出、应用起不来。
        Crash.recordException(e, "op" to "yuketang_session_read")
        runCatching { prefs.clear() }
        null
    }

    private companion object {
        const val FILE = "yuketang_session_secure"
        const val LEGACY_FILE = "yuketang_session"
        const val KEY_SESSION_ID = "sessionid"
        const val KEY_CSRF = "csrftoken"
        const val KEY_UV_ID = "uv_id"
        const val KEY_UNIVERSITY_ID = "university_id"
        const val KEY_XTBZ = "xtbz"
        const val KEY_ORIGIN = "origin"
        const val KEY_RAW_COOKIE = "raw_cookie"
    }
}

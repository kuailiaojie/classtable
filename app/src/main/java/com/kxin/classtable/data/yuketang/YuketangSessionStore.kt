package com.kxin.classtable.data.yuketang

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.kxin.classtable.data.Crash
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 雨课堂会话的加密持久化(EncryptedSharedPreferences),与云账号会话分开一个文件。
 * 登录态约两周失效:失效后由 [YuketangClient] 判定并提示重新登录。
 */
@Singleton
class YuketangSessionStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "yuketang_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    private val state = MutableStateFlow(readFromPrefs())

    /**
     * 当前会话流:登录/登出后设置页与详情页要立刻跟着变,所以除了 [read] 之外还提供这条
     * 可观察的流(单例,全应用共享同一份)。
     */
    val session: StateFlow<YuketangSession?> = state.asStateFlow()

    fun read(): YuketangSession? = state.value

    /** 传 null 表示登出。 */
    fun write(session: YuketangSession?) {
        val p = prefs ?: return
        runCatching {
            p.edit().clear().apply()
            if (session != null) {
                p.edit()
                    .putString(KEY_SESSION_ID, session.sessionId)
                    .putString(KEY_CSRF, session.csrfToken)
                    .putString(KEY_UV_ID, session.uvId)
                    .putString(KEY_UNIVERSITY_ID, session.universityId)
                    .putString(KEY_XTBZ, session.xtbz)
                    .putString(KEY_ORIGIN, session.origin)
                    .putString(KEY_RAW_COOKIE, session.rawCookie)
                    .apply()
            }
        }.onFailure { Crash.recordException(it, "op" to "yuketang_session_write") }
        state.value = session
    }

    private fun readFromPrefs(): YuketangSession? {
        val p = prefs ?: return null
        return runCatching {
            val sessionId = p.getString(KEY_SESSION_ID, null) ?: return null
            YuketangSession(
                sessionId = sessionId,
                csrfToken = p.getString(KEY_CSRF, null).orEmpty(),
                uvId = p.getString(KEY_UV_ID, null).orEmpty(),
                universityId = p.getString(KEY_UNIVERSITY_ID, null).orEmpty(),
                xtbz = p.getString(KEY_XTBZ, null).orEmpty()
                    .ifBlank { YuketangSession.DEFAULT_XTBZ },
                origin = p.getString(KEY_ORIGIN, null).orEmpty()
                    .ifBlank { YuketangSession.DEFAULT_ORIGIN },
                rawCookie = p.getString(KEY_RAW_COOKIE, null).orEmpty(),
            )
        }.getOrElse { e ->
            // 主密钥失效 / 密文损坏时 getString 会抛:清掉重来,退化为未登录。
            // 这里在构造期被调用(→ 单例初始化),不捕获的话异常会从 Hilt 构造链冒出、应用起不来。
            Crash.recordException(e, "op" to "yuketang_session_read")
            runCatching { p.edit().clear().apply() }
            null
        }
    }

    private companion object {
        const val KEY_SESSION_ID = "sessionid"
        const val KEY_CSRF = "csrftoken"
        const val KEY_UV_ID = "uv_id"
        const val KEY_UNIVERSITY_ID = "university_id"
        const val KEY_XTBZ = "xtbz"
        const val KEY_ORIGIN = "origin"
        const val KEY_RAW_COOKIE = "raw_cookie"
    }
}

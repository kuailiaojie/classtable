package com.kxin.classtable.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话的加密持久化。值由 [SecurePrefs] 用 Android Keystore 的 AES-GCM 加密后落盘,
 * 进程被杀后恢复登录态;refreshToken 为长期凭证,必须加密存。
 *
 * 旧版 EncryptedSharedPreferences 的数据**不迁移**:换加密格式后旧密文已读不出来,
 * 退化为未登录、用户重登一次即可(会话可重新取得,不像课程数据那样不可丢)。
 */
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = SecurePrefs(context, FILE)

    init {
        // 清掉旧的 EncryptedSharedPreferences 文件:内容已不可读,留着只是占空间。
        runCatching { context.deleteSharedPreferences(LEGACY_FILE) }
    }

    fun read(): AuthSession? = runCatching {
        val uid = prefs.getString(KEY_UID) ?: return null
        val idToken = prefs.getString(KEY_ID_TOKEN) ?: return null
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN) ?: return null
        AuthSession(
            uid = uid,
            email = prefs.getString(KEY_EMAIL)?.takeIf { it.isNotBlank() },
            idToken = idToken,
            refreshToken = refreshToken,
            expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L),
        )
    }.getOrElse { e ->
        // 密钥失效 / 密文损坏(ROM 升级、Keystore 重置、Auto Backup 还原)时读会失败:
        // 清掉重来、退化为未登录;绝不让异常冒到启动路径上。
        Crash.recordException(e, "op" to "session_read")
        runCatching { prefs.clear() }
        null
    }

    fun write(session: AuthSession?) {
        runCatching {
            prefs.clear()
            if (session != null) {
                prefs.putString(KEY_UID, session.uid)
                prefs.putString(KEY_EMAIL, session.email.orEmpty())
                prefs.putString(KEY_ID_TOKEN, session.idToken)
                prefs.putString(KEY_REFRESH_TOKEN, session.refreshToken)
                prefs.putLong(KEY_EXPIRES_AT, session.expiresAt)
            }
        }.onFailure { Crash.recordException(it, "op" to "session_write") }
    }

    private companion object {
        const val FILE = "auth_session_secure"
        const val LEGACY_FILE = "auth_session"
        const val KEY_UID = "uid"
        const val KEY_EMAIL = "email"
        const val KEY_ID_TOKEN = "id_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"
    }
}

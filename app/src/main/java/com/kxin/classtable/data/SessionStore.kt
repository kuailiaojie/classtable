package com.kxin.classtable.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话的加密持久化(EncryptedSharedPreferences)。
 * 进程被杀后恢复登录态;refreshToken 为长期凭证,必须加密落盘。
 */
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "auth_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    fun read(): AuthSession? {
        val p = prefs ?: return null
        val uid = p.getString(KEY_UID, null) ?: return null
        val idToken = p.getString(KEY_ID_TOKEN, null) ?: return null
        val refreshToken = p.getString(KEY_REFRESH_TOKEN, null) ?: return null
        return AuthSession(
            uid = uid,
            email = p.getString(KEY_EMAIL, null),
            idToken = idToken,
            refreshToken = refreshToken,
            expiresAt = p.getLong(KEY_EXPIRES_AT, 0L),
        )
    }

    fun write(session: AuthSession?) {
        val p = prefs ?: return
        p.edit().clear().apply()
        if (session != null) {
            p.edit()
                .putString(KEY_UID, session.uid)
                .putString(KEY_EMAIL, session.email)
                .putString(KEY_ID_TOKEN, session.idToken)
                .putString(KEY_REFRESH_TOKEN, session.refreshToken)
                .putLong(KEY_EXPIRES_AT, session.expiresAt)
                .apply()
        }
    }

    private companion object {
        const val KEY_UID = "uid"
        const val KEY_EMAIL = "email"
        const val KEY_ID_TOKEN = "id_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"
    }
}

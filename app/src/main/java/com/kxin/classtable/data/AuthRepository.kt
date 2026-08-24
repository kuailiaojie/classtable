package com.kxin.classtable.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Email/Password 认证(REST 版,经反代访问 Firebase Auth)。未登录 = 访客本地模式。
 * 会话(idToken/refreshToken)存 [SessionStore](加密);idToken 过期自动用 refreshToken 刷新。
 * 错误约定:[FirebaseApiException] 为业务错误不重试;网络类(超时/断连)自动重试最多 3 次。
 */
@Singleton
class AuthRepository @Inject constructor(
    private val gateway: FirebaseGateway,
    private val sessionStore: SessionStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _currentUser = MutableStateFlow<AuthSession?>(null)
    val currentUser: StateFlow<AuthSession?> = _currentUser.asStateFlow()

    init {
        // 进程重启后从加密存储恢复登录态
        scope.launch { _currentUser.value = sessionStore.read() }
    }

    val isSignedIn: Boolean get() = _currentUser.value != null
    val uid: String? get() = _currentUser.value?.uid

    suspend fun signUp(email: String, password: String): Result<Unit> {
        val r = authCall {
            gateway.auth(
                "accounts:signUp",
                JSONObject().apply {
                    put("email", email)
                    put("password", password)
                    put("returnSecureToken", true)
                },
            )
        }
        if (r.isSuccess) Analytics.log("sign_up", "email" to email)
        return r
    }

    suspend fun signIn(email: String, password: String): Result<Unit> {
        val r = authCall {
            gateway.auth(
                "accounts:signInWithPassword",
                JSONObject().apply {
                    put("email", email)
                    put("password", password)
                    put("returnSecureToken", true)
                },
            )
        }
        if (r.isSuccess) Analytics.log("sign_in", "email" to email)
        return r
    }

    suspend fun signOut() {
        sessionStore.write(null)
        _currentUser.value = null
    }

    /** 发送密码重置邮件;无论邮箱是否存在都返回成功(防枚举)。 */
    suspend fun sendPasswordReset(email: String): Result<Unit> = authCall(expectSession = false) {
        gateway.auth(
            "accounts:sendOobCode",
            JSONObject().apply {
                put("requestType", "PASSWORD_RESET")
                put("email", email)
            },
        )
    }

    /** 取未过期的 idToken;过期则用 refreshToken 刷新并更新会话。失败返回 null。 */
    suspend fun freshIdToken(): String? {
        val session = _currentUser.value ?: return null
        if (System.currentTimeMillis() < session.expiresAt - 60_000) return session.idToken
        return refreshToken(session) ?: session.idToken
    }

    private suspend fun refreshToken(session: AuthSession): String? = runCatching {
        val resp = gateway.auth(
            "token",
            JSONObject().apply {
                put("grant_type", "refresh_token")
                put("refresh_token", session.refreshToken)
            },
        )
        val refreshed = session.copy(
            idToken = resp.getString("id_token"),
            refreshToken = resp.optString("refresh_token", session.refreshToken),
            expiresAt = System.currentTimeMillis() + resp.optLong("expires_in", 3600) * 1000,
        )
        sessionStore.write(refreshed)
        _currentUser.value = refreshed
        refreshed.idToken
    }.getOrNull()

    /**
     * 认证请求统一包装:单次 15 秒超时;**网络类失败(超时/断连)自动重试最多 3 次**;
     * 业务错误([FirebaseApiException],如密码过短/邮箱已注册)不重试,直接返回。
     * [expectSession]=true 时把响应解析为会话并持久化(登录/注册);false 时仅校验成功
     * (如发送重置邮件,sendOobCode 响应不含 token 字段)。
     */
    private suspend fun authCall(
        expectSession: Boolean = true,
        block: suspend () -> JSONObject,
    ): Result<Unit> {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                withTimeout(15_000) {
                    val resp = block()
                    if (expectSession) {
                        val session = AuthSession(
                            uid = resp.getString("localId"),
                            email = resp.optString("email").ifBlank { null },
                            idToken = resp.getString("idToken"),
                            refreshToken = resp.getString("refreshToken"),
                            expiresAt = System.currentTimeMillis() + resp.optLong("expiresIn", 3600) * 1000,
                        )
                        sessionStore.write(session)
                        _currentUser.value = session
                    }
                }
                return Result.success(Unit)
            } catch (e: TimeoutCancellationException) {
                lastError = IOException("连接超时:请检查网络(直连不稳定时应用会自动重试)")
            } catch (e: IOException) {
                // 网络层错误(连接/读取超时、断连):重试
                lastError = e
            } catch (e: Exception) {
                // 业务错误(FirebaseApiException 等):重试无意义,直接返回
                return Result.failure(e)
            }
            if (attempt < 2) delay(2_000)
        }
        return Result.failure(lastError ?: IOException("网络错误"))
    }
}

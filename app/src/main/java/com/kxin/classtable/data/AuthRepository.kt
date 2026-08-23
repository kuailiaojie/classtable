package com.kxin.classtable.data

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.inject.Inject
import javax.inject.Singleton

/** Email/Password 认证。未登录 = 访客本地模式,登录后才启用同步。 */
@Singleton
class AuthRepository @Inject constructor() {
    private val auth = FirebaseAuth.getInstance()

    val currentUser: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        trySend(auth.currentUser)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    val isSignedIn: Boolean get() = auth.currentUser != null

    suspend fun signUp(email: String, password: String): Result<Unit> {
        val r = authCall { auth.createUserWithEmailAndPassword(email, password) }
        if (r.isSuccess) Analytics.log("sign_up", "email" to email)
        return r
    }

    suspend fun signIn(email: String, password: String): Result<Unit> {
        val r = authCall { auth.signInWithEmailAndPassword(email, password) }
        if (r.isSuccess) Analytics.log("sign_in", "email" to email)
        return r
    }

    suspend fun signOut() {
        auth.signOut()
    }

    /** 发送密码重置邮件;无论邮箱是否存在都返回成功(防枚举)。 */
    suspend fun sendPasswordReset(email: String): Result<Unit> = authCall {
        auth.sendPasswordResetEmail(email)
    }

    /**
     * 认证请求统一包装:单次 15 秒超时;**网络类失败(超时)自动重试最多 3 次**,
     * 直连抖动时显著提高成功率;业务类错误(密码过短/邮箱格式/账号未启用)不重试,直接返回。
     */
    private suspend fun authCall(block: () -> Task<*>): Result<Unit> {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                withTimeout(15_000) {
                    suspendCancellableCoroutine { cont ->
                        block().addOnCompleteListener { task ->
                            runCatching {
                                if (task.isSuccessful) {
                                    cont.resume(Unit)
                                } else {
                                    cont.resumeWithException(task.exception ?: Exception("认证失败"))
                                }
                            }
                        }
                    }
                }
                return Result.success(Unit)
            } catch (e: TimeoutCancellationException) {
                lastError = IOException("连接超时:请检查网络(直连不稳定时应用会自动重试)")
            } catch (e: Exception) {
                // 业务错误(如密码太短、邮箱格式、登录方式未启用):重试无意义,直接返回
                return Result.failure(e)
            }
            if (attempt < 2) delay(2_000)
        }
        return Result.failure(lastError ?: IOException("网络错误"))
    }
}

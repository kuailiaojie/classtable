package com.kxin.classtable.data

import android.content.Context
import android.util.Log
import com.google.firebase.appcheck.FirebaseAppCheck

/**
 * App Check 门面:给经反代的 Auth / Firestore 请求附上一枚 Attestation token,让「谁在调用」
 * 可被 Firebase 端校验,避免反代地址被当免费中转滥用。
 *
 * 设计取向是**失败放行**:Play Integrity 依赖 GMS,华为等无 GMS 设备拿不到 token;
 * 任何获取失败都返回 null(调用方不加头),绝不因此阻断登录与同步。token 缓存在内存里,
 * 临近过期才刷新 —— 不为每个请求都做一次 attestation。
 *
 * provider 按 build type 分源集提供(见 `src/debug` 与 `src/release` 的 [installAppCheckProvider]):
 * debug 用 Debug provider(release 包里没有那个类),release 用 Play Integrity。
 */
object AppCheck {
    private const val TAG = "AppCheck"

    /** 提前量:剩余有效期不足这么多就换一枚新的。 */
    private const val REFRESH_MARGIN_MS = 5 * 60 * 1000L

    @Volatile
    private var initialized = false

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var expiresAt = 0L

    /** 在 Application 启动、任何 Firebase 调用之前安装 provider。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        runCatching {
            installAppCheckProvider(FirebaseAppCheck.getInstance())
        }.onFailure { Log.w(TAG, "installAppCheckProviderFactory failed: ${it.message}") }
    }

    /** 一枚可用的 token;不可用时返回 null(失败放行)。 */
    suspend fun token(): String? {
        val current = cachedToken
        if (current != null && System.currentTimeMillis() < expiresAt - REFRESH_MARGIN_MS) {
            return current
        }
        return runCatching {
            val result = FirebaseAppCheck.getInstance().getAppCheckToken(false).awaitResult()
            cachedToken = result.token
            expiresAt = result.expireTimeMillis
            result.token
        }.onFailure { Log.w(TAG, "token unavailable: ${it.message}") }.getOrNull()
    }
}

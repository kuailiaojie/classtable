package com.kxin.classtable.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProvider
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.AppCheckToken
import com.google.firebase.appcheck.FirebaseAppCheck
import com.kxin.classtable.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * App Check 门面:给经反代的 Auth / Firestore 请求附上一枚 Attestation token,让「谁在调用」
 * 可被 Firebase 端校验,避免反代地址被当免费中转滥用。
 *
 * 用**自定义 provider** 而不是 Play Integrity:后者要 Google Play 开发者账号、且围绕 Play 分发,
 * 本项目是 GitHub 侧载、还要照顾无 GMS 设备,都不合身。这里让客户端带上本包**签名证书的
 * SHA-256**,向自建反代换取一枚由 `firebase-admin` 签发的 App Check token —— 不依赖 GMS,
 * 侧载与无 GMS 设备同样能出 token。它是「抬高门槛」而非硬件级证明:能挡掉抄了反代地址白嫖配额
 * 的滥用,但签名指纹可被有心人模仿。
 *
 * 设计取向仍是**失败放行**:任何一步失败都返回 null(调用方不加头),绝不因此阻断登录与同步。
 * token 缓存在内存里,临近过期才刷新。
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
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                ProxyAppCheckProviderFactory(context.applicationContext),
            )
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

/** 自定义 provider:向自建反代换取 App Check token。 */
private class ProxyAppCheckProviderFactory(private val context: Context) : AppCheckProviderFactory {
    override fun create(firebaseApp: FirebaseApp): AppCheckProvider = ProxyAppCheckProvider(context)
}

private class ProxyAppCheckProvider(private val context: Context) : AppCheckProvider {
    /** SDK 在后台线程调用;用 Tasks.call 把阻塞网络放到它自己的执行器上。 */
    override fun getToken(): Task<AppCheckToken> = Tasks.call {
        val request = JSONObject()
            .put("signingSha256", signingSha256(context))
            .put("package", context.packageName)
        val resp = postJson("${BuildConfig.FIREBASE_PROXY_URL}/appcheck/token", request)
        ProxyAppCheckToken(
            resp.getString("token"),
            resp.optLong("expireTimeMillis", System.currentTimeMillis() + 30 * 60 * 1000L),
        )
    }

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IOException("appcheck token HTTP $status")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}

/** `AppCheckToken` 在 SDK 里是抽象类:自定义 provider 需要自己实现这两个 getter。 */
private class ProxyAppCheckToken(
    private val value: String,
    private val expiresAtMillis: Long,
) : AppCheckToken() {
    override fun getToken(): String = value

    override fun getExpireTimeMillis(): Long = expiresAtMillis
}

/** 本应用签名证书的 SHA-256(大写十六进制、无分隔),供反代校验是不是正版包。 */
private fun signingSha256(context: Context): String {
    val pm = context.packageManager
    val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners
            ?: info.signingInfo?.signingCertificateHistory
        signers?.firstOrNull()
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            .signatures?.firstOrNull()
    } ?: throw IllegalStateException("no signing certificate")
    return MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}

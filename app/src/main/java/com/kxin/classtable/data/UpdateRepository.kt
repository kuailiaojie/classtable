package com.kxin.classtable.data

import android.content.Context
import com.kxin.classtable.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val isNewer: Boolean,
    val notes: String,
    val apkUrl: String,
    val releaseUrl: String,
)

/**
 * 应用更新:经自建 Netlify 反代 `/version` 获取最新版本(服务端代为查询 GitHub Release,
 * 客户端不直连 GitHub),再经 `/apk` 流式下载安装包到本机。
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val proxyBase: String = BuildConfig.FIREBASE_PROXY_URL.trimEnd('/')
    private val versionUrl: String get() = "$proxyBase/version"

    /** 查询最新版本并判断是否有更新。 */
    suspend fun check(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val json = getJson(versionUrl)
            val latest = json.optString("versionName").trim().removePrefix("v")
            if (latest.isBlank()) throw IOException("版本信息为空")
            val rawApkUrl = json.optString("apkUrl").trim()
            UpdateInfo(
                currentVersion = BuildConfig.VERSION_NAME,
                latestVersion = latest,
                isNewer = isNewer(BuildConfig.VERSION_NAME, latest),
                notes = json.optString("notes"),
                apkUrl = when {
                    rawApkUrl.isBlank() -> ""
                    rawApkUrl.startsWith("http") -> rawApkUrl
                    else -> proxyBase + if (rawApkUrl.startsWith("/")) rawApkUrl else "/$rawApkUrl"
                },
                releaseUrl = json.optString("releaseUrl"),
            )
        }
    }

    /** 下载安装包到 filesDir/updates/app-update.apk,按 Content-Length 回调进度。 */
    suspend fun downloadApk(apkUrl: String, onProgress: (Int) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (apkUrl.isBlank()) throw IOException("没有可下载的安装包")
                val dir = File(context.filesDir, "updates").apply { mkdirs() }
                val out = File(dir, "app-update.apk")
                val conn = URL(apkUrl).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 60_000
                    conn.instanceFollowRedirects = true
                    val status = conn.responseCode
                    if (status !in 200..299) throw IOException("下载失败(HTTP $status)")
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        out.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var read = input.read(buffer)
                            var sum = 0L
                            while (read > 0) {
                                output.write(buffer, 0, read)
                                sum += read
                                if (total > 0) {
                                    onProgress(((sum * 100) / total).toInt().coerceIn(0, 100))
                                }
                                read = input.read(buffer)
                            }
                        }
                    }
                    if (total > 0 && out.length() != total) throw IOException("安装包不完整,请重试")
                    out
                } finally {
                    conn.disconnect()
                }
            }
        }

    /**
     * 是否该做一次自动检查:开关打开 + 距上次超过 [CHECK_INTERVAL_MS]。
     * 节流与开关都落在设置里(原来记在 filesDir/update/meta.json,与其余配置分散)。
     */
    suspend fun shouldAutoCheck(): Boolean {
        val s = settings.currentSettings()
        if (!s.autoCheckUpdate) return false
        return System.currentTimeMillis() - s.lastUpdateCheckAt > CHECK_INTERVAL_MS
    }

    suspend fun markChecked() = settings.markUpdateChecked()

    /** 用户已忽略的版本(该版本不再主动提示)。 */
    suspend fun dismissedVersion(): String = settings.currentSettings().dismissedVersion

    suspend fun ignoreVersion(version: String) = settings.setDismissedVersion(version)

    private fun getJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/json")
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                throw IOException(message.ifBlank { "请求失败(HTTP $status)" })
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    /** 语义化版本比较:逐段比数字,较大的段决定新旧。 */
    private fun isNewer(current: String, latest: String): Boolean {
        val a = parseVersion(current)
        val b = parseVersion(latest)
        for (i in 0 until maxOf(a.size, b.size)) {
            val cur = a.getOrElse(i) { 0 }
            val lat = b.getOrElse(i) { 0 }
            if (lat != cur) return lat > cur
        }
        return false
    }

    private fun parseVersion(version: String): List<Int> = version
        .trim()
        .removePrefix("v")
        .split('.')
        .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    private companion object {
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}

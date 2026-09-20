package com.kxin.classtable.data

import android.content.Context
import com.kxin.classtable.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val isNewer: Boolean,
    val notes: String,
    val apkUrl: String,
    /** 安装包字节数(0 = 服务端未提供,进度只能按已下载量显示)。 */
    val apkSize: Long,
    /** 安装包 SHA-256(空 = 不校验)。 */
    val apkSha256: String,
    val releaseUrl: String,
)

/** 安装包下载状态。挂在仓库上而不是页面 ViewModel 上,所以来回切页不会中断下载、也不会丢进度。 */
sealed interface DownloadState {
    data object Idle : DownloadState

    data class Running(val received: Long, val total: Long) : DownloadState {
        val percent: Int
            get() = if (total > 0) ((received * 100) / total).toInt().coerceIn(0, 100) else 0
    }

    data class Ready(val file: File) : DownloadState

    data class Failed(val message: String) : DownloadState
}

/**
 * 应用更新:经自建 Netlify 反代 `/version` 获取最新版本(服务端代查 GitHub Release,
 * 客户端不直连 GitHub),再经 `/apk` 下载安装包。
 *
 * 下载这条链上踩过的坑,都在这里处理掉:
 * 1. **不要依赖 Content-Length**:代理/边缘会把响应改成 chunked,长度缺失或与解压后长度不一致,
 *    早期实现据此算进度会永远停在 0%,据此做完整性校验还会把好包判成「不完整」。进度改为按
 *    **已写入字节数** 计算,总长取服务端 `/version` 给出的 `apkSize`。
 * 2. **明确 `Accept-Encoding: identity`**:带 gzip 的响应里长度是压缩后的,进度与校验都会错位。
 * 3. **分段 + 断点续传 + 重试**:单次请求限 [CHUNK_BYTES],既绕开代理/边缘的单响应上限
 *    (超出会被截断成坏包),也顺带支持弱网重连续传;每段失败重试 [MAX_ATTEMPTS] 次。
 * 4. **SHA-256 校验**(服务端从 GitHub Release 的 asset digest 透出)+ 安装前校验包内版本号:
 *    宁可报错让用户重试,也不把一个坏包装到系统安装器里。
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val proxyBase: String = BuildConfig.FIREBASE_PROXY_URL.trimEnd('/')
    private val versionUrl: String get() = "$proxyBase/version"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

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
                apkSize = json.optLong("apkSize", 0L),
                apkSha256 = json.optString("apkSha256").trim(),
                releaseUrl = json.optString("releaseUrl"),
            )
        }
    }

    /** 开始下载。已在下载中时忽略重复调用(下载跑在仓库自己的作用域里,离开页面不会中断)。 */
    fun startDownload(info: UpdateInfo) {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch { runDownload(info) }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _download.value = DownloadState.Idle
    }

    /** 用户看过错误/装完之后清掉状态,便于重新点「检查更新」。 */
    fun clearDownloadState() {
        if (downloadJob?.isActive != true) _download.value = DownloadState.Idle
    }

    private suspend fun runDownload(info: UpdateInfo) {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val target = File(dir, APK_NAME)
        val part = File(dir, "$APK_NAME.part")
        val total = info.apkSize
        try {
            // 同一版本已经下载并校验过 → 直接复用,不重复下载
            if (target.exists() && isReusable(target, info)) {
                _download.value = DownloadState.Ready(target)
                return
            }
            if (target.exists()) target.delete()
            // 上次留下的分段文件可续传;比目标还大说明是坏数据,丢弃重来
            if (part.exists() && total > 0 && part.length() > total) part.delete()
            _download.value = DownloadState.Running(if (part.exists()) part.length() else 0L, total)

            fetchAll(info, part) { received ->
                _download.value = DownloadState.Running(received, total)
            }

            if (total > 0 && part.length() != total) {
                throw IOException("安装包不完整(已下载 ${part.length()} / $total 字节)")
            }
            verifyDigest(part, info.apkSha256)?.let { throw IOException(it) }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            verifyApkVersion(target, info.latestVersion)?.let {
                target.delete()
                throw IOException(it)
            }
            _download.value = DownloadState.Ready(target)
        } catch (cancelled: CancellationException) {
            _download.value = DownloadState.Idle
            throw cancelled
        } catch (error: Exception) {
            _download.value = DownloadState.Failed(error.message ?: "下载失败")
        }
    }

    /** 循环取段直到文件达到 [UpdateInfo.apkSize];服务端总长未知时读到流结束为止。 */
    private suspend fun fetchAll(info: UpdateInfo, part: File, onProgress: (Long) -> Unit) {
        while (true) {
            val have = if (part.exists()) part.length() else 0L
            val total = info.apkSize
            if (total > 0 && have >= total) return
            val end = if (total > 0) {
                (minOf(have + CHUNK_BYTES, total) - 1).coerceAtLeast(have)
            } else {
                have + CHUNK_BYTES - 1
            }
            val result = requestChunk(info.apkUrl, part, have, end, onProgress)
            val grown = result.length - have
            if (grown <= 0L) {
                if (total > 0) throw IOException("下载中断,请重试")
                return
            }
            // 服务端忽略 Range:一次只给到被截断的长度,再请求也不会更长,直接报错而不是空转
            if (!result.ranged && total > 0 && result.length < total) {
                throw IOException("下载被服务端截断(${result.length} / $total 字节),请稍后重试")
            }
        }
    }

    private data class ChunkResult(val length: Long, val ranged: Boolean)

    private suspend fun requestChunk(
        url: String,
        part: File,
        from: Long,
        to: Long,
        onProgress: (Long) -> Unit,
    ): ChunkResult {
        var lastError: Exception? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return readOnce(url, part, from, to, onProgress)
            } catch (error: IOException) {
                lastError = error
                if (attempt < MAX_ATTEMPTS) delay(RETRY_BACKOFF_MS * attempt)
            }
        }
        throw lastError ?: IOException("下载失败")
    }

    private fun readOnce(
        url: String,
        part: File,
        from: Long,
        to: Long,
        onProgress: (Long) -> Unit,
    ): ChunkResult {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            // 明确要求不压缩:压缩响应的长度与解压后不一致,进度与校验都会错位
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Range", "bytes=$from-$to")
        }
        try {
            val status = conn.responseCode
            if (status !in 200..299) throw IOException(errorMessage(conn, status))
            val ranged = status == 206
            // 服务端忽略 Range 时返回 200 + 整包:必须从头覆盖写,不能追加
            val startAt = if (ranged) from else 0L
            var written = startAt
            RandomAccessFile(part, "rw").use { file ->
                file.setLength(startAt)
                file.seek(startAt)
                conn.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var lastReported = startAt
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        file.write(buffer, 0, read)
                        written += read
                        if (written - lastReported >= PROGRESS_STEP_BYTES) {
                            lastReported = written
                            onProgress(written)
                        }
                    }
                }
            }
            onProgress(written)
            return ChunkResult(written, ranged)
        } finally {
            conn.disconnect()
        }
    }

    /** 服务端返回的错误正文里有 message 就透出来,否则给个通用文案。 */
    private fun errorMessage(conn: HttpURLConnection, status: Int): String {
        val text = runCatching {
            conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull().orEmpty()
        val message = runCatching {
            JSONObject(text).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()
        return message.ifBlank { "下载失败(HTTP $status)" }
    }

    /** 已下载的安装包是否可直接复用:长度、SHA-256、包内版本号三者都对得上。 */
    private fun isReusable(file: File, info: UpdateInfo): Boolean {
        if (info.apkSize > 0 && file.length() != info.apkSize) return false
        if (verifyDigest(file, info.apkSha256) != null) return false
        return parseVersion(
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.versionName.orEmpty(),
        ) == parseVersion(info.latestVersion)
    }

    /** 校验 SHA-256;服务端未提供摘要时跳过。返回 null 表示通过,否则返回错误文案。 */
    private fun verifyDigest(file: File, expected: String): String? {
        if (expected.isBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return if (actual.equals(expected, ignoreCase = true)) {
            null
        } else {
            "安装包校验失败(内容不完整或已损坏),请重新下载"
        }
    }

    /** 读 APK 内的版本号,确认下载到的确实是提示的那个版本。返回 null 表示通过。 */
    private fun verifyApkVersion(file: File, expected: String): String? {
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: return "安装包无法解析,请重新下载"
        val actual = archive.versionName.orEmpty()
        if (expected.isBlank() || actual.isBlank()) return null
        return if (parseVersion(actual) == parseVersion(expected)) {
            null
        } else {
            "安装包版本(v$actual)与提示版本(v$expected)不一致,请重新下载"
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
            conn.setRequestProperty("Accept-Encoding", "identity")
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
        const val APK_NAME = "app-update.apk"

        /** 单次请求字节数:留足余量给代理的单响应上限(Netlify 函数约 6MB,超出会被截断)。 */
        const val CHUNK_BYTES = 4L * 1024 * 1024
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_STEP_BYTES = 256L * 1024
        const val MAX_ATTEMPTS = 4
        const val RETRY_BACKOFF_MS = 900L
    }
}

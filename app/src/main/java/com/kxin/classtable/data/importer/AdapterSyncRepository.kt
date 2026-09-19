package com.kxin.classtable.data.importer

import android.content.Context
import com.kxin.classtable.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** 适配器数据来源:内置(APK assets)或已同步(filesDir 缓存)。 */
enum class AdapterSource { BUNDLED, SYNCED }

data class AdapterSyncInfo(
    val source: AdapterSource,
    val schoolCount: Int,
    val adapterCount: Int,
    val scriptCount: Int,
    val syncedAt: Long,
    val generatedAt: String,
)

/**
 * 适配器同步:从自建 Netlify 站点拉取打包好的 bundle(学校索引 + 适配器配置 + 全部脚本),
 * 落到 filesDir/warehouse/,供 [WarehouseIndex] 缓存优先读取。
 * 网络栈与 FirebaseGateway 同风格(HttpURLConnection + org.json,零第三方库)。
 */
@Singleton
class AdapterSyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cacheRoot: File get() = File(context.filesDir, WarehouseIndex.CACHE_DIR)
    private val metaFile: File get() = File(cacheRoot, "meta.json")
    private val bundleUrl: String get() = "${BuildConfig.SITE_BASE_URL}/warehouse/bundle.json"

    /** 当前适配器数据状态(缓存存在则视为已同步)。 */
    suspend fun info(): AdapterSyncInfo = withContext(Dispatchers.IO) { readInfo() }

    /** 同步云端 bundle;失败时保留原缓存(不破坏可用性)。 */
    suspend fun sync(): Result<AdapterSyncInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val bundle = fetchBundle()
            val schools = bundle.optJSONArray("schools") ?: JSONArray()
            val adapters = bundle.optJSONArray("adapters") ?: JSONArray()
            val scripts = bundle.optJSONObject("scripts") ?: JSONObject()
            if (schools.length() == 0 || adapters.length() == 0) {
                throw IOException("云端适配器数据为空")
            }
            writeCache(schools, adapters, scripts)
            val info = AdapterSyncInfo(
                source = AdapterSource.SYNCED,
                schoolCount = schools.length(),
                adapterCount = adapters.length(),
                scriptCount = scripts.length(),
                syncedAt = System.currentTimeMillis(),
                generatedAt = bundle.optString("generatedAt"),
            )
            writeMeta(info)
            info
        }
    }

    /** 清除缓存,回到内置数据。 */
    suspend fun clear(): Result<AdapterSyncInfo> = withContext(Dispatchers.IO) {
        runCatching {
            if (cacheRoot.exists()) cacheRoot.deleteRecursively()
            readInfo()
        }
    }

    private fun fetchBundle(): JSONObject {
        val conn = URL(bundleUrl).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            val status = conn.responseCode
            if (status !in 200..299) throw IOException("同步失败(HTTP $status)")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    /** 先写临时目录再整体替换,避免同步中断留下半新半旧的缓存。 */
    private fun writeCache(schools: JSONArray, adapters: JSONArray, scripts: JSONObject) {
        val tmp = File(context.filesDir, "${WarehouseIndex.CACHE_DIR}.tmp")
        if (tmp.exists()) tmp.deleteRecursively()
        tmp.mkdirs()
        File(tmp, "index.json").writeText(
            JSONObject().put("schools", schools).toString(),
            Charsets.UTF_8,
        )
        File(tmp, "adapters.json").writeText(
            JSONObject().put("adapters", adapters).toString(),
            Charsets.UTF_8,
        )
        val resRoot = File(tmp, "resources")
        val keys = scripts.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.contains("..")) continue
            val target = File(resRoot, key)
            target.parentFile?.mkdirs()
            target.writeText(scripts.getString(key), Charsets.UTF_8)
        }
        if (cacheRoot.exists()) cacheRoot.deleteRecursively()
        if (!tmp.renameTo(cacheRoot)) {
            tmp.copyRecursively(cacheRoot, overwrite = true)
            tmp.deleteRecursively()
        }
    }

    private fun writeMeta(info: AdapterSyncInfo) {
        cacheRoot.mkdirs()
        metaFile.writeText(
            JSONObject()
                .put("schoolCount", info.schoolCount)
                .put("adapterCount", info.adapterCount)
                .put("scriptCount", info.scriptCount)
                .put("syncedAt", info.syncedAt)
                .put("generatedAt", info.generatedAt)
                .toString(),
            Charsets.UTF_8,
        )
    }

    private fun readInfo(): AdapterSyncInfo {
        val meta = if (metaFile.isFile) {
            runCatching { JSONObject(metaFile.readText(Charsets.UTF_8)) }.getOrNull()
        } else {
            null
        }
        if (meta != null) {
            return AdapterSyncInfo(
                source = AdapterSource.SYNCED,
                schoolCount = meta.optInt("schoolCount"),
                adapterCount = meta.optInt("adapterCount"),
                scriptCount = meta.optInt("scriptCount"),
                syncedAt = meta.optLong("syncedAt"),
                generatedAt = meta.optString("generatedAt"),
            )
        }
        return AdapterSyncInfo(
            source = AdapterSource.BUNDLED,
            schoolCount = WarehouseIndex.loadSchools(context).getOrDefault(emptyList()).size,
            adapterCount = WarehouseIndex.loadAdapters(context).getOrDefault(emptyList()).size,
            scriptCount = 0,
            syncedAt = 0L,
            generatedAt = "",
        )
    }
}

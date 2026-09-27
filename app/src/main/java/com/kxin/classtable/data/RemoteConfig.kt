package com.kxin.classtable.data

import android.content.Context
import android.util.Log
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.kxin.classtable.BuildConfig

/**
 * 远程配置门面:只做远程开关 / 灰度,**不接管任何确定性逻辑**(提醒排程、适配器同步、
 * 更新通道一律不动)。
 *
 * 默认值写死在 [DEFAULTS] 里 —— App 以代码为唯一兜底真相:拉取失败、或大陆取不到
 * (Remote Config 走 Google 域名)时,一律回落默认值,离线优先不受影响。
 */
object RemoteConfig {
    private const val TAG = "RemoteConfig"

    private val DEFAULTS = mapOf<String, Any>(
        "splash_enabled" to true,
        "update_prerelease_default" to false,
    )

    private val instance
        get() = runCatching { FirebaseRemoteConfig.getInstance() }.getOrNull()

    fun init(context: Context) {
        val config = instance ?: return
        runCatching {
            config.setConfigSettingsAsync(
                FirebaseRemoteConfigSettings.Builder()
                    .setMinimumFetchIntervalInSeconds(if (BuildConfig.DEBUG) 0 else 3600)
                    .build(),
            )
            config.setDefaultsAsync(DEFAULTS)
        }.onFailure { Log.w(TAG, "init failed: ${it.message}") }
    }

    /** 后台拉取并激活一次;失败静默(继续用默认值 / 上次缓存)。 */
    suspend fun fetch() {
        val config = instance ?: return
        runCatching { config.fetchAndActivate().awaitResult() }
            .onFailure { Log.d(TAG, "fetch skipped: ${it.message}") }
    }

    /**
     * 读取布尔开关。**关键**:`setDefaultsAsync` 是异步的,在此之前 SDK 里既没有默认值也没有远端值
     * (source = STATIC),直接 `getBoolean` 会拿到 false —— 那会误关开屏这类默认开启的功能。
     * 因此 SOURCE_STATIC 时一律回落到代码默认值。
     */
    fun getBoolean(key: String): Boolean {
        val fallback = DEFAULTS[key] as? Boolean ?: false
        val value = instance?.let { runCatching { it.getValue(key) }.getOrNull() } ?: return fallback
        if (value.source == FirebaseRemoteConfig.VALUE_SOURCE_STATIC) return fallback
        return runCatching { value.asBoolean() }.getOrDefault(fallback)
    }

    fun getString(key: String): String {
        val fallback = DEFAULTS[key] as? String ?: ""
        val value = instance?.let { runCatching { it.getValue(key) }.getOrNull() } ?: return fallback
        if (value.source == FirebaseRemoteConfig.VALUE_SOURCE_STATIC) return fallback
        return runCatching { value.asString() }.getOrDefault(fallback)
    }
}

package com.kxin.classtable.data

import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Crashlytics 门面:面包屑日志 + 非致命异常。任何失败静默,不影响功能。
 *
 * 用它把过去被 `runCatching {}` 静默吞掉的失败(同步 / 导入 / AI 识别…)记下来,
 * 让「用户说同步不上」这类问题在控制台可查;业务性错误([FirebaseApiException] 等)不要当崩溃上报。
 */
object Crash {
    private val instance
        get() = runCatching { FirebaseCrashlytics.getInstance() }.getOrNull()

    fun setUserId(uid: String?) {
        instance?.let { runCatching { it.setUserId(uid.orEmpty()) } }
    }

    /** 面包屑:记录状态转换,崩溃时可还原现场。 */
    fun log(message: String) {
        instance?.let { runCatching { it.log(message) } }
    }

    /** 非致命异常;附加键值对便于定位。 */
    fun recordException(throwable: Throwable, vararg context: Pair<String, Any>) {
        val crashlytics = instance ?: return
        runCatching {
            context.forEach { (key, value) -> crashlytics.setCustomKey(key, value.toString()) }
            crashlytics.recordException(throwable)
        }
    }
}

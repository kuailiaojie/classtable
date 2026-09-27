package com.kxin.classtable.data

import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace

/**
 * Performance Monitoring 门面:手动 trace 关键耗时。
 *
 * 启动、屏幕渲染与 HTTP 请求由 SDK 自动采集,这里只补业务级耗时(同步 / 导入 / AI 识别)。
 * trace 失败绝不影响原逻辑 —— start/stop 都包在 runCatching 里。
 */
object Perf {
    /** 跑一段并记录耗时,原样返回其结果。 */
    suspend fun <T> trace(name: String, block: suspend () -> T): T {
        val trace: Trace? = runCatching { FirebasePerformance.getInstance().newTrace(name) }.getOrNull()
        runCatching { trace?.start() }
        return try {
            block()
        } finally {
            runCatching { trace?.stop() }
        }
    }
}

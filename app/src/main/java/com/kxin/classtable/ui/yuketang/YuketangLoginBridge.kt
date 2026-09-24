package com.kxin.classtable.ui.yuketang

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * 雨课堂登录页的 JS 桥。
 *
 * 目前只有一件事:抓包探针 —— 登录页的网页请求里能看到登录走的接口;公告接口已于 2026-09
 * 用同样的办法确认并固化(见 [com.kxin.classtable.data.yuketang.YuketangClient.ANNOUNCEMENT_PATH])。
 *
 * Debug 构建注入一段 fetch/XMLHttpRequest 包装脚本,把网页发出的请求 URL 打到 logcat
 * (tag `YuketangProbe`);Release 构建不注入。
 *
 * 注意约束:浏览器只用来**打开页面 / 确认登录 / 取 Cookie** —— 不用它去调接口或读响应体取业务
 * 数据,那些一律走应用自己的 HTTP 客户端。
 */
class YuketangLoginBridge {

    @JavascriptInterface
    fun reportApi(url: String, method: String) {
        Log.i(PROBE_TAG, "${method.ifBlank { "GET" }} $url")
    }

    companion object {
        const val PROBE_TAG = "YuketangProbe"
    }
}

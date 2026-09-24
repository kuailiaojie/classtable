package com.kxin.classtable.ui.yuketang

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * 雨课堂登录页的 JS 桥。
 *
 * 目前只有一件事:抓包探针。`yuketang-api.md` 未收录**公告端点**,因此在 Debug 构建里
 * 注入一段 fetch/XMLHttpRequest 包装脚本,把网页发出的请求 URL 打到 logcat
 * (tag `YuketangProbe`);用真机登录后打开某课程的「公告」页,即可读出真实端点,
 * 再把它收敛进 [com.kxin.classtable.data.yuketang.YuketangClient.ANNOUNCEMENT_PATHS]。
 *
 * Release 构建不注入探针。
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

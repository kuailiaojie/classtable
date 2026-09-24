package com.kxin.classtable.data

import android.content.Context
import android.webkit.WebSettings

/**
 * 桌面 Chrome UA 的构造。教务导入与雨课堂都需要「桌面版」UA:
 * 前者因为适配脚本按桌面 DOM 写,后者因为网页版接口按桌面端分发。
 *
 * 关键点:**版本号必须取自当前 WebView 的真实内核**(`WebSettings.getDefaultUserAgent`),
 * 只把平台标识换成桌面。硬编码某个 Chrome 版本会让网站按那个版本下发新语法 JS,
 * 而本机内核跑不了 → 页面直接白屏。
 */
object WebUserAgent {
    fun desktop(context: Context): String {
        val current = WebSettings.getDefaultUserAgent(context)
        val webKit = Regex("AppleWebKit/[^\\s]+", RegexOption.IGNORE_CASE).find(current)?.value
        val chromium = Regex("(?:Chrome|Chromium)/[0-9.]+", RegexOption.IGNORE_CASE).find(current)?.value
        val safari = Regex("Safari/[^\\s]+", RegexOption.IGNORE_CASE).find(current)?.value
        if (webKit == null || chromium == null || safari == null) {
            return current
                .replaceFirst(Regex("\\([^)]*\\)"), "(X11; Linux x86_64)")
                .replace("; wv", "")
                .replace(" Mobile ", " ")
        }
        return "Mozilla/5.0 (X11; Linux x86_64) $webKit (KHTML, like Gecko) $chromium $safari"
    }
}

package com.kxin.classtable.ui.importer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.kxin.classtable.BuildConfig
import java.net.URL
import java.util.Locale

/**
 * 教务导入用的 WebView 层:设置、Cookie、JS 桥注册、客户端与弹窗。
 *
 * 从 [ImportScreen] 里抽出 —— 这块与 Compose 步骤 UI 无关,只是「怎么把一个 WebView 配好」。
 * 关键点(每条都是踩过的坑):
 * - `useWideViewPort` / `loadWithOverviewMode` / `setInitialScale`:固定宽度页面否则只渲染左上角。
 * - `onRenderProcessGone`:渲染进程被系统回收后必须重建,否则永久白屏(只能杀 App)。
 * - `onReceivedSslError`:默认实现静默取消,页面就是白的,这里取消并明确告知原因。
 * - SSO 重写文档后注入对象会失效,`onPageStarted`/`onPageFinished` 都要重新注册 + 补垫片。
 */
@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
internal fun configureImportWebView(
    webView: WebView,
    context: Context,
    isPopup: Boolean,
    bridge: ImportBridge,
    desktopUa: String,
    onPageStart: () -> Unit,
    onError: (String) -> Unit,
    onNavState: (WebView?) -> Unit,
    onCreatePopup: (WebView) -> Unit,
    onClosePopup: () -> Unit,
    onRendererGone: (WebView) -> Unit,
) {
    webView.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    // 远程调试开关已移至 ClasstableApp.onCreate 进程全局配置
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        userAgentString = desktopUa
        useWideViewPort = true
        loadWithOverviewMode = true
        layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
        textZoom = 100
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(!isPopup)
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        cacheMode = WebSettings.LOAD_DEFAULT
        allowFileAccess = false
        allowContentAccess = false
    }
    webView.setInitialScale(0)
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }
    webView.addJavascriptInterface(bridge, "AndroidBridgeNative")
    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
            handleExternalNavigation(context, request.url, onError)

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            onNavState(view)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            onPageStart()
            // 重新注册注入对象再注入垫片(顺序不能反)。
            //
            // 教务系统普遍走 CAS / 深信服 aTrust 这类 SSO:页面会在**同一个 JS 上下文里重写文档**
            // (document.write / 前端路由),WebView 随之把旧的注入对象标记为「非注入」,
            // 之后该文档上的每一次桥调用都会抛
            //   "Java bridge method can't be invoked on a non-injected object"。
            // 我们原来只在创建 WebView 时注册一次,于是 SSO 跳转过的页面里桥整个失效:
            // 垫片把异常吞成 null → 适配器判成「用户取消」→ 弹「已取消导入」。
            // addJavascriptInterface 可重复调用,会为当前上下文换上一个新的可用对象;
            // 垫片是实时取 window.AndroidBridgeNative 的,因此旧垫片也会立刻恢复。
            view?.addJavascriptInterface(bridge, "AndroidBridgeNative")
            view?.evaluateJavascript(ImportBridge.SHIM_JS, null)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            // 有些页面加载完才动态改 DOM,再补一次(垫片幂等;注入对象同样需要刷新)
            view?.addJavascriptInterface(bridge, "AndroidBridgeNative")
            view?.evaluateJavascript(ImportBridge.SHIM_JS, null)
            onNavState(view)
        }

        @Suppress("DEPRECATION")
        override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
            super.onReceivedError(view, errorCode, description, failingUrl)
            // 只有主文档失败才覆盖页面:子资源(图片/统计脚本)失败不该遮挡整页
            if (failingUrl == null || view?.url == null || failingUrl == view.url) {
                onError(friendlyLoadError(errorCode, description))
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                onError(friendlyLoadError(error?.errorCode ?: -1, error?.description?.toString()))
            }
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?,
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            if (request?.isForMainFrame == true) {
                onError("服务器返回错误 ${errorResponse?.statusCode ?: "未知"}")
            }
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            handler?.cancel()
            val failing = error?.url
            // 子资源证书问题不该遮挡整个页面;主文档证书失败才会白屏,必须说明
            if (failing != null && view?.url != null && failing != view.url) return
            val reason = when (error?.primaryError) {
                SslError.SSL_UNTRUSTED -> "证书不受信任(学校自签证书常见)"
                SslError.SSL_EXPIRED -> "证书已过期"
                SslError.SSL_NOTYETVALID -> "证书尚未生效"
                SslError.SSL_DATE_INVALID -> "证书日期无效"
                SslError.SSL_IDMISMATCH -> "证书与域名不匹配"
                else -> "证书校验失败"
            }
            onError(reason + (failing?.let { ":$it" } ?: ""))
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            // 返回 true 表示由我们处理;否则系统可能直接杀掉整个应用
            view?.let(onRendererGone)
            return true
        }
    }
    webView.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            val text = consoleMessage?.message()
            // 桥的备用通道:垫片直连注入对象失败时(SSO 重写文档后对象被判成「非注入」),
            // 会把调用以固定前缀写到控制台。这类消息属于桥流量,交给桥处理 —— 既不写
            // logcat(负载可能很大),也不要当普通页面日志刷屏。
            if (text != null && bridge.dispatchConsoleCall(text)) return true
            // 其余控制台输出(含白屏、导入中止的真实 JS 报错)统一落到 logcat(tag: ImportWebView),
            // 用 Log.i 以免被部分 ROM 过滤掉。
            consoleMessage?.let {
                Log.i("ImportWebView", "console=${it.message()} @${it.sourceId()}:${it.lineNumber()}")
            }
            return super.onConsoleMessage(consoleMessage)
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?,
        ): Boolean {
            if (isPopup) {
                // 二层弹窗:不阻断,直接在当前弹窗 WebView 中承载目标页面,解决高校 CAS 二次跳转无响应问题
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = view
                resultMsg.sendToTarget()
                return true
            }
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            val child = WebView(context)
            configureImportWebView(
                webView = child,
                context = context,
                isPopup = true,
                bridge = bridge,
                desktopUa = desktopUa,
                onPageStart = onPageStart,
                onError = onError,
                onNavState = onNavState,
                onCreatePopup = onCreatePopup,
                onClosePopup = onClosePopup,
                onRendererGone = onRendererGone,
            )
            transport.webView = child
            resultMsg.sendToTarget()
            onCreatePopup(child)
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            if (isPopup) onClosePopup()
        }
    }
}

/** 非 http(s) 链接交给系统处理(WebView 自己加载会留下空白页);返回 true 表示已拦截。 */
internal fun handleExternalNavigation(context: Context, uri: Uri, onError: (String) -> Unit): Boolean {
    val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
    if (scheme.isEmpty() || scheme == "http" || scheme == "https") return false
    val intent = runCatching {
        if (scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        else Intent(Intent.ACTION_VIEW, uri)
    }.getOrNull() ?: return true
    intent.component = null
    intent.selector = null
    if (intent.resolveActivity(context.packageManager) == null) return true
    runCatching { context.startActivity(intent) }
        .onFailure { onError("无法打开外部链接") }
    return true
}

/** WebView 错误码 → 用户可读的中文原因(避免白屏无提示)。 */
internal fun friendlyLoadError(errorCode: Int, raw: String?): String = when (errorCode) {
    WebViewClient.ERROR_HOST_LOOKUP -> "无法解析域名,请确认教务网址是否正确"
    WebViewClient.ERROR_CONNECT -> "无法连接到服务器,教务系统可能未开放外网访问"
    WebViewClient.ERROR_TIMEOUT -> "连接超时,请检查网络后重试"
    WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "SSL 证书校验失败,该站点证书可能不受信任"
    // WebViewClient.ERROR_CLEARTEXT_NOT_PERMITTED(API 26 起,值为 -29,SDK 无公开常量)
    -29 -> "系统禁止访问 http 明文页面,请使用 https 地址或联系开发者放行"
    WebViewClient.ERROR_UNSUPPORTED_SCHEME -> "不支持的网址协议"
    WebViewClient.ERROR_BAD_URL -> "网址格式不正确"
    else -> raw?.takeIf { it.isNotBlank() } ?: "未知错误(错误码 $errorCode)"
}

/** 补全协议:用户常直接填 jw.xxx.edu.cn,统一补 https://。 */
internal fun normalizeImportUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}

/** 仅校验能否解析出主机名,避免明显无效的网址进入 WebView。 */
internal fun isValidImportUrl(url: String): Boolean {
    if (url.isBlank()) return false
    return !runCatching { URL(url).host }.getOrNull().isNullOrBlank()
}

/**
 * 把 JS 桥注册到当前页面(注入脚本前刷新一次)。
 *
 * 单独包一层只为挂 `@SuppressLint("JavascriptInterface")`:lint 的该检查看不穿 Compose
 * lambda 里 `target` 的静态类型,会误报「加入的接口 (T) 没有带 @JavascriptInterface 的方法」——
 * 而 [ImportBridge] 的桥方法其实都带了注解。
 */
@SuppressLint("JavascriptInterface")
internal fun WebView.installJavascriptBridge(bridge: ImportBridge) {
    addJavascriptInterface(bridge, "AndroidBridgeNative")
}

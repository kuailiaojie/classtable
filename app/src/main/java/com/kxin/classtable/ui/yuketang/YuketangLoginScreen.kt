package com.kxin.classtable.ui.yuketang

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.BuildConfig
import com.kxin.classtable.data.WebUserAgent
import com.kxin.classtable.data.yuketang.YuketangRepository
import com.kxin.classtable.data.yuketang.YuketangSession
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuOutlineButton
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class YuketangLoginViewModel @Inject constructor(
    private val repository: YuketangRepository,
) : ViewModel() {
    var verifying by mutableStateOf(false)
        private set

    /** 给用户看的一句提示(null = 无)。 */
    var message by mutableStateOf<String?>(null)
        private set

    fun clearMessage() {
        message = null
    }

    /**
     * 用当前页面的 Cookie 完成登录。
     *
     * 先**保存**会话再校验:`false`(服务端明确说没登录)才回滚;网络不通只提示,
     * 不把刚拿到的 Cookie 白白丢掉。
     */
    fun completeLogin(cookie: String?, origin: String, onSuccess: () -> Unit) {
        val session = YuketangSession.fromCookie(cookie, origin)
        if (session == null) {
            message = "没读到登录态。请先在网页里登录,并随便打开一门课程的页面,再点「完成登录」。"
            return
        }
        viewModelScope.launch {
            verifying = true
            repository.saveSession(session)
            val result = runCatching { repository.verifyLogin() }
            verifying = false
            result.fold(
                onSuccess = { ok ->
                    if (ok) {
                        message = null
                        onSuccess()
                    } else {
                        repository.logout()
                        message = "雨课堂说这个登录态无效(可能还没真正登录)。请在网页里登录后再试一次。"
                    }
                },
                onFailure = {
                    message = "登录态已保存,但校验时网络不通。稍后在「设置 → 雨课堂」点「立即刷新公告」可以再校验一次。"
                },
            )
        }
    }
}

/** 登录页:WebView 里自行登录,点「完成登录」把 Cookie 抓下来加密保存。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YuketangLoginScreen(
    nav: NavHostController,
    viewModel: YuketangLoginViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val context = LocalContext.current
    val holder = remember { YuketangWebHolder() }
    val bridge = remember { YuketangLoginBridge() }
    val desktopUa = remember { WebUserAgent.desktop(context) }

    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var popup by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(START_URL) }
    var generation by remember { mutableIntStateOf(0) }
    var restoreUrl by remember { mutableStateOf<String?>(null) }

    fun syncNavState(view: WebView?) {
        canGoBack = view?.canGoBack() == true
        canGoForward = view?.canGoForward() == true
        view?.url?.takeIf { it.isNotBlank() }?.let { currentUrl = it }
    }

    /** 关闭 window.open 出来的页面(以 holder 为准,避免闭包读到过期的 compose 值)。 */
    fun closePopup() {
        val view = holder.popup ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        runCatching { view.destroy() }
        holder.popup = null
        popup = null
        syncNavState(holder.webView)
    }

    DisposableEffect(Unit) { onDispose { closePopup() } }
    if (popup != null) BackHandler { closePopup() }

    fun finishLogin() {
        val origin = YuketangOrigin.of(currentUrl)
        // sessionid 是 HttpOnly,只有原生 CookieManager 读得到;先 flush 再读,免得拿到半份。
        CookieManager.getInstance().flush()
        val cookie = runCatching { CookieManager.getInstance().getCookie(origin) }.getOrNull()
        viewModel.completeLogin(cookie, origin) {
            Toast.makeText(context, "雨课堂登录成功", Toast.LENGTH_SHORT).show()
            nav.popBackStack()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        YohakuTopBar(title = "登录雨课堂", onBack = { nav.popBackStack() })

        Column(modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding)) {
            Text(
                text = currentUrl,
                style = YohakuType.timeMono,
                color = colors.neutral7,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "在下方网页里登录(扫码或账号密码皆可),再点「完成登录」· 登录态只加密存在本机",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            viewModel.message?.let { hint ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = hint, style = YohakuType.label12, color = colors.warning)
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = YohakuDimens.screenPadding),
        ) {
            // 渲染进程被回收后 generation 自增,AndroidView 重新执行 factory 建新页面
            key(generation) {
                AndroidView(
                    factory = { ctx ->
                        val existing = holder.webView
                        if (existing != null) {
                            existing
                        } else {
                            val created = WebView(ctx)
                            configureYuketangWebView(
                                webView = created,
                                context = ctx,
                                isPopup = false,
                                bridge = bridge,
                                desktopUa = desktopUa,
                                onPageStarted = {
                                    loadError = null
                                    viewModel.clearMessage()
                                },
                                onError = { loadError = it },
                                onNavState = { view ->
                                    if (view === holder.popup || holder.popup == null) syncNavState(view)
                                },
                                onCreatePopup = { child ->
                                    holder.popup = child
                                    popup = child
                                    syncNavState(child)
                                },
                                onClosePopup = { closePopup() },
                                onRendererGone = { dead ->
                                    restoreUrl = restoreUrl ?: dead.url
                                    (dead.parent as? ViewGroup)?.removeView(dead)
                                    runCatching { dead.destroy() }
                                    holder.popup?.let { p ->
                                        (p.parent as? ViewGroup)?.removeView(p)
                                        runCatching { p.destroy() }
                                    }
                                    holder.popup = null
                                    popup = null
                                    holder.webView = null
                                    generation++
                                },
                            )
                            created.loadUrl(restoreUrl ?: START_URL)
                            restoreUrl = null
                            holder.webView = created
                            created
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // window.open / target=_blank 的页面(部分学校的统一身份认证走这里)
            popup?.let { popupView ->
                Box(modifier = Modifier.fillMaxSize().background(colors.paper)) {
                    AndroidView(factory = { popupView }, modifier = Modifier.fillMaxSize())
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "新窗口",
                            style = YohakuType.label12,
                            color = colors.neutral7,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "关闭",
                            style = YohakuType.copy13,
                            color = colors.accent,
                            modifier = Modifier.clickable { closePopup() }.padding(8.dp),
                        )
                    }
                }
            }

            loadError?.let { message ->
                Column(
                    modifier = Modifier.fillMaxSize().background(colors.paper),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = "页面加载失败", style = YohakuType.copy15, color = colors.neutral9)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = message,
                        style = YohakuType.label12,
                        color = colors.neutral7,
                        modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    YohakuOutlineButton(
                        text = "重新加载",
                        onClick = {
                            loadError = null
                            holder.webView?.loadUrl(currentUrl.ifBlank { START_URL })
                        },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = YohakuDimens.screenPadding, vertical = YohakuDimens.gapTight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "‹ 后退",
                style = YohakuType.copy13,
                color = if (canGoBack) colors.neutral7 else colors.neutral5,
                modifier = Modifier
                    .clickable(enabled = canGoBack) {
                        holder.active()?.goBack()
                    }
                    .padding(vertical = 8.dp),
            )
            Text(
                text = "前进 ›",
                style = YohakuType.copy13,
                color = if (canGoForward) colors.neutral7 else colors.neutral5,
                modifier = Modifier
                    .clickable(enabled = canGoForward) {
                        holder.active()?.goForward()
                    }
                    .padding(vertical = 8.dp),
            )
            Text(
                text = "刷新",
                style = YohakuType.copy13,
                color = colors.neutral7,
                modifier = Modifier
                    .clickable { holder.active()?.reload() }
                    .padding(vertical = 8.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            YohakuButton(
                text = if (viewModel.verifying) "校验中…" else "完成登录",
                onClick = { finishLogin() },
                enabled = !viewModel.verifying,
            )
        }
    }
}

/** 主页面与弹窗共用的持有者:关掉弹窗后主页面还在,不必重建。 */
private class YuketangWebHolder {
    var webView: WebView? = null
    var popup: WebView? = null

    /** 当前可见的那一个(弹窗优先)。 */
    fun active(): WebView? = popup ?: webView
}

/** 起始页:课程列表在登录后可见。 */
private const val START_URL = "https://changjiang.yuketang.cn/"

/** 只认雨课堂的域名;其它(如 SSO 中转页)一律回落到默认站点。 */
private object YuketangOrigin {
    fun of(url: String): String {
        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        return if (host.endsWith("yuketang.cn")) "https://$host" else YuketangSession.DEFAULT_ORIGIN
    }
}

/**
 * Debug 构建才注入的抓包探针:把页面发出的请求 URL 报到 logcat(tag `YuketangProbe`)。
 * 用途单一 —— 找出**公告端点**;正式包里不注入。
 */
private val PROBE_JS = """
    (function(){
      if (window.__yktProbeReady) { return; }
      window.__yktProbeReady = true;
      try {
        var originalFetch = window.fetch;
        if (originalFetch) {
          window.fetch = function(){
            try {
              var first = arguments[0];
              var url = (first && first.url) ? first.url : first;
              var method = (arguments[1] && arguments[1].method) || 'GET';
              AndroidYuketangNative.reportApi(String(url), String(method));
            } catch (e) {}
            return originalFetch.apply(this, arguments);
          };
        }
        var originalOpen = XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open = function(method, url){
          try { AndroidYuketangNative.reportApi(String(url), String(method || 'GET')); } catch (e) {}
          return originalOpen.apply(this, arguments);
        };
      } catch (e) {}
    })();
"""

/**
 * 雨课堂登录 WebView 的统一配置。要点与教务导入一致(白屏成因一一对应):
 * 桌面 UA(网页版接口按桌面端分发)、第三方 Cookie(统一身份认证跨域跳转)、
 * `onCreateWindow`(window.open)、渲染进程回收后重建、SSL 错误明确告知。
 */
@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
private fun configureYuketangWebView(
    webView: WebView,
    context: Context,
    isPopup: Boolean,
    bridge: YuketangLoginBridge,
    desktopUa: String,
    onPageStarted: () -> Unit,
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
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }
    webView.addJavascriptInterface(bridge, "AndroidYuketangNative")
    webView.webViewClient = object : WebViewClient() {
        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            onNavState(view)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            onPageStarted()
            // SSO 页面会在同一 JS 上下文里重写文档,旧注入对象随之失效 → 每次导航都重新注册
            view?.addJavascriptInterface(bridge, "AndroidYuketangNative")
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            view?.addJavascriptInterface(bridge, "AndroidYuketangNative")
            if (BuildConfig.DEBUG) {
                view?.evaluateJavascript(PROBE_JS, null)
            }
            onNavState(view)
        }

        @Suppress("DEPRECATION")
        override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
            super.onReceivedError(view, errorCode, description, failingUrl)
            // 只有主文档失败才覆盖页面:子资源失败不该遮挡整页
            if (failingUrl == null || view?.url == null || failingUrl == view.url) {
                onError(description ?: "错误 $errorCode")
            }
        }

        override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                onError(error?.description?.toString() ?: "网络错误")
            }
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: android.webkit.WebResourceRequest?,
            errorResponse: android.webkit.WebResourceResponse?,
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            if (request?.isForMainFrame == true) {
                onError("服务器返回错误 ${errorResponse?.statusCode ?: "未知"}")
            }
        }

        override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
            handler?.cancel()
            onError("证书校验失败:${error?.url ?: "未知地址"}")
        }

        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
            view?.let(onRendererGone)
            return true
        }
    }
    webView.webChromeClient = object : WebChromeClient() {
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message?,
        ): Boolean {
            if (isPopup) return false
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            val child = WebView(context)
            configureYuketangWebView(
                webView = child,
                context = context,
                isPopup = true,
                bridge = bridge,
                desktopUa = desktopUa,
                onPageStarted = onPageStarted,
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

        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
            consoleMessage?.let {
                Log.i("YuketangWebView", "console=${it.message()} @${it.sourceId()}:${it.lineNumber()}")
            }
            return super.onConsoleMessage(consoleMessage)
        }
    }
}

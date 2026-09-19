package com.kxin.classtable.ui.importer

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject

/**
 * WebView ⇄ 适配脚本桥(实现 shiguang_warehouse 的 AndroidBridge* 契约)。
 * 脚本注入即自执行,通过桥回传课程/作息/学期配置;弹窗类调用原生实现。
 *
 * 形参约定:所有 Promise 方法由垫片把实参**归一化到固定个数**后再追加 callbackId,
 * 因此这里可以按位置取参,不必迁就各适配器 3 参 / 4 参的不同写法。
 */
@SuppressLint("SetJavaScriptEnabled")
class ImportBridge(
    private val webViewProvider: () -> WebView,
    private val mainHandler: Handler,
    private val onToast: (String) -> Unit,
    private val onCoursesJson: (String) -> Unit,
    private val onPresetTimeSlotsJson: (String) -> Unit,
    private val onCourseConfigJson: (String) -> Unit,
    private val onDone: () -> Unit,
) {

    @JavascriptInterface
    fun showToast(message: String) {
        mainHandler.post { onToast(message ?: "") }
    }

    @JavascriptInterface
    fun notifyTaskCompletion() {
        mainHandler.post { onDone() }
    }

    /** 适配脚本自身抛出的异常上报(wrapScript 捕获同步异常,异步异常由脚本自己 showToast)。 */
    @JavascriptInterface
    fun reportError(message: String) {
        val text = (message ?: "").trim().take(200).ifBlank { "未知错误" }
        mainHandler.post { onToast("适配脚本错误:$text") }
    }

    @JavascriptInterface
    fun saveImportedCourses(json: String, callbackId: String) {
        mainHandler.post {
            onCoursesJson(json ?: "")
            resolve(callbackId, "true")
        }
    }

    @JavascriptInterface
    fun savePresetTimeSlots(json: String, callbackId: String) {
        mainHandler.post {
            onPresetTimeSlotsJson(json ?: "")
            resolve(callbackId, "true")
        }
    }

    @JavascriptInterface
    fun saveCourseConfig(json: String, callbackId: String) {
        mainHandler.post {
            onCourseConfigJson(json ?: "")
            resolve(callbackId, "true")
        }
    }

    /**
     * 单按钮提示框。契约(拾光 Bridge):`showAlert(titleText, contentText, confirmText)`,
     * 只有 confirmText 指定的**一个**确认按钮,官方固定 resolve(true)。
     *
     * 这里此前被实现成「确定 / 取消」两按钮,且取消、点击外部都会 resolve(false)。
     * 而适配器普遍写成 `const ok = await showAlert(...); if (!ok) return;`
     * (如 AHSZU、AHZYYGZ、BBGU、CAUC 等),于是点一下取消或误触外部就会让脚本
     * 静默退出——表现为「弹出一个取消导入的框,然后什么都没导入」。
     */
    @JavascriptInterface
    fun showAlert(title: String, message: String, confirmText: String, callbackId: String) {
        mainHandler.post {
            AlertDialog.Builder(webViewProvider().context)
                .setTitle(title.ifBlank { "提示" })
                .setMessage(message ?: "")
                .setPositiveButton(confirmText.ifBlank { "确定" }) { _, _ -> resolve(callbackId, "true") }
                // 协议里没有「取消」这条路径:禁止返回键 / 点击外部关闭,
                // 否则脚本会拿到 false 直接 return,用户看不到任何失败原因
                .setCancelable(false)
                .show()
        }
    }

    /**
     * 输入框。第 4 个参数 validator 是**适配器页面里的校验函数名**(如 validateYearInput);
     * 之前被忽略,导致非法输入直接进入后续流程。现在校验不通过会带着原输入重新弹出。
     */
    @JavascriptInterface
    fun showPrompt(title: String, message: String, defaultText: String, validator: String, callbackId: String) {
        mainHandler.post {
            showPromptDialog(webViewProvider(), title, message, defaultText, validator, callbackId)
        }
    }

    private fun showPromptDialog(
        webView: WebView,
        title: String,
        message: String,
        defaultText: String,
        validator: String,
        callbackId: String,
    ) {
        val input = EditText(webView.context).apply { setText(defaultText ?: "") }
        AlertDialog.Builder(webView.context)
            .setTitle(title ?: "")
            .setMessage(message ?: "")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val value = input.text.toString()
                if (validator.isBlank() || validator == "null") {
                    resolve(callbackId, JSONObject.quote(value))
                } else {
                    validate(webView, validator, value) { ok ->
                        if (ok) {
                            resolve(callbackId, JSONObject.quote(value))
                        } else {
                            showPromptDialog(webView, title, message, value, validator, callbackId)
                        }
                    }
                }
            }
            .setNegativeButton("取消") { _, _ -> resolve(callbackId, "null") }
            .setOnCancelListener { resolve(callbackId, "null") }
            .show()
    }

    /** 调用页面内校验函数:不存在 / 抛异常时按通过处理,避免误拦。 */
    private fun validate(webView: WebView, validator: String, value: String, onResult: (Boolean) -> Unit) {
        val js = "(function(){try{var f=window[" + JSONObject.quote(validator) + "];" +
            "if(typeof f!=='function')return true;return f(" + JSONObject.quote(value) + ")!==false;" +
            "}catch(e){return true;}})()"
        webView.post {
            runCatching {
                webView.evaluateJavascript(js) { result ->
                    mainHandler.post { onResult(result != "false") }
                }
            }.onFailure { mainHandler.post { onResult(true) } }
        }
    }

    /** 单选列表:defaultIndex 预选(之前被忽略),取消返回 null。 */
    @JavascriptInterface
    fun showSingleSelection(title: String, itemsJson: String, defaultIndex: Int, callbackId: String) {
        mainHandler.post {
            val items = runCatching {
                val arr = JSONArray(itemsJson ?: "[]")
                (0 until arr.length()).map { arr.optString(it) }
            }.getOrDefault(emptyList())
            if (items.isEmpty()) {
                resolve(callbackId, if (defaultIndex >= 0) defaultIndex.toString() else "null")
                return@post
            }
            val checked = if (defaultIndex in items.indices) defaultIndex else 0
            var selected = checked
            val dialog = AlertDialog.Builder(webViewProvider().context)
                .setTitle(title ?: "")
                .setSingleChoiceItems(items.toTypedArray(), checked) { _, which -> selected = which }
                .setPositiveButton("确定") { _, _ -> resolve(callbackId, selected.toString()) }
                .setNegativeButton("取消") { _, _ -> resolve(callbackId, "null") }
                .setOnCancelListener { resolve(callbackId, "null") }
                .create()
            dialog.show()
        }
    }

    private fun resolve(callbackId: String, jsValue: String) {
        val webView = webViewProvider()
        webView.post {
            runCatching {
                webView.evaluateJavascript("window.__resolve(${JSONObject.quote(callbackId)}, $jsValue);", null)
            }
        }
    }

    companion object {
        /**
         * 桌面 Chrome UA:多数教务系统按 UA 分发页面,适配脚本按桌面 DOM 编写,
         * 用 WebView 默认(移动)UA 会拿到移动版页面导致解析不到课表。
         *
         * 关键点:**版本号必须取自当前 WebView 的真实内核**(`WebSettings.getDefaultUserAgent`),
         * 只把平台标识换成桌面。硬编码某个版本(如以前写死的 Chrome/120)会让网站按那个版本
         * 下发新语法 JS,而本机内核跑不了 → 页面直接白屏。
         */
        fun desktopUserAgent(context: Context): String {
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

        /** 把适配脚本包进 try/catch,同步异常经桥上报(异步异常由脚本自身 showToast 反馈)。 */
        fun wrapScript(script: String): String =
            "(function(){try{\n$script\n}catch(e){AndroidBridgeNative.reportError((e&&e.message)||String(e));}})();"

        /**
         * 桥垫片:定义 AndroidBridge / AndroidBridgePromise / shiguangBridge* / __resolve。
         *
         * 关键点:
         * 1) 不劫持 window.onerror / unhandledrejection —— 曾把**教务网页自身**的脚本报错
         *    当成「适配脚本错误」弹给用户(表现为莫名其妙的报错 Toast),现改为只上报适配脚本
         *    自身抛出的异常。
         * 2) 每个方法先把实参补齐到固定个数,再追加 callbackId,解决各适配器
         *    showAlert 3 参 / 4 参等写法差异造成的形参错位(错位会让 Promise 一直挂到超时)。
         */
        const val SHIM_JS = """
            (function(){
              window.__resolvers = {};
              window.__resolve = function(id, value){
                var entry = window.__resolvers[id];
                if (entry) {
                  delete window.__resolvers[id];
                  if (entry.timer) { clearTimeout(entry.timer); }
                  entry.resolve(value);
                }
              };
              function arg(a, i){ var v = a[i]; return (v === undefined || v === null) ? '' : v; }
              function callNative(method, args){
                return new Promise(function(resolve){
                  var id = 'cb_' + (Math.random() * 1e9 | 0);
                  var timer = setTimeout(function(){
                    if (window.__resolvers[id]) { delete window.__resolvers[id]; resolve(null); }
                  }, 60000);
                  window.__resolvers[id] = { resolve: resolve, timer: timer };
                  try { AndroidBridgeNative[method].apply(null, args.concat([id])); }
                  catch(e) { delete window.__resolvers[id]; clearTimeout(timer); resolve(null); }
                });
              }
              window.AndroidBridge = {
                showToast: function(m){ try { AndroidBridgeNative.showToast(String(m)); } catch(e) {} },
                notifyTaskCompletion: function(){ try { AndroidBridgeNative.notifyTaskCompletion(); } catch(e) {} }
              };
              window.AndroidBridgePromise = {
                showAlert: function(){
                  var a = arguments;
                  return callNative('showAlert', [arg(a,0), arg(a,1), arg(a,2)]);
                },
                showPrompt: function(){
                  var a = arguments;
                  return callNative('showPrompt', [arg(a,0), arg(a,1), arg(a,2), arg(a,3)]);
                },
                showSingleSelection: function(){
                  var a = arguments;
                  // items 可能是数组,也可能是已序列化的 JSON 字符串。
                  // 原生侧按 JSON 解析(JSONArray),而 WebView 把 JS 数组传给 Java 的
                  // String 形参时只会 toString() 成逗号串(不是 JSON),会让列表解析失败、
                  // 选择框根本不弹出。官方 polyfill 同样在这里先做序列化。
                  var items = a[1];
                  var itemsJson = (typeof items === 'string') ? items : JSON.stringify(items || []);
                  var idx = (typeof a[2] === 'number') ? a[2] : -1;
                  return callNative('showSingleSelection', [arg(a,0), itemsJson, idx]);
                },
                saveImportedCourses: function(){ return callNative('saveImportedCourses', [arg(arguments,0)]); },
                savePresetTimeSlots: function(){ return callNative('savePresetTimeSlots', [arg(arguments,0)]); },
                saveCourseConfig: function(){ return callNative('saveCourseConfig', [arg(arguments,0)]); }
              };
              window.shiguangBridge = window.AndroidBridge;
              window.shiguangBridgePromise = window.AndroidBridgePromise;
            })();
        """
    }
}

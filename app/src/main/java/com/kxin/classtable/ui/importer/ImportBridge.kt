package com.kxin.classtable.ui.importer

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Handler
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject

/**
 * WebView ⇄ 适配脚本桥(实现 shiguang_warehouse 的 AndroidBridge* 契约)。
 * 脚本注入即自执行,通过桥回传课程/作息/学期配置;弹窗类调用原生实现。
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

    /** 适配脚本异常上报:之前脚本报错完全静默,现在统一以 toast 反馈。 */
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

    @JavascriptInterface
    fun showAlert(title: String, message: String, positive: String, callbackId: String) {
        mainHandler.post {
            val dialog = AlertDialog.Builder(webViewProvider().context)
                .setTitle(title ?: "")
                .setMessage(message ?: "")
                .setPositiveButton(positive.ifBlank { "确定" }) { _, _ -> resolve(callbackId, "true") }
                .setNegativeButton("取消") { _, _ -> resolve(callbackId, "false") }
                .setOnCancelListener { resolve(callbackId, "false") }
                .create()
            dialog.show()
        }
    }

    @JavascriptInterface
    fun showPrompt(title: String, message: String, defaultText: String, validator: String, callbackId: String) {
        mainHandler.post {
            val input = EditText(webViewProvider().context).apply { setText(defaultText ?: "") }
            val dialog = AlertDialog.Builder(webViewProvider().context)
                .setTitle(title ?: "")
                .setMessage(message ?: "")
                .setView(input)
                .setPositiveButton("确定") { _, _ -> resolve(callbackId, JSONObject.quote(input.text.toString())) }
                .setNegativeButton("取消") { _, _ -> resolve(callbackId, "null") }
                .setOnCancelListener { resolve(callbackId, "null") }
                .create()
            dialog.show()
        }
    }

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
            val dialog = AlertDialog.Builder(webViewProvider().context)
                .setTitle(title ?: "")
                .setItems(items.toTypedArray()) { _, which -> resolve(callbackId, which.toString()) }
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
         */
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** 把适配脚本包进 try/catch,同步异常经桥上报(异步异常由 unhandledrejection 兜底)。 */
        fun wrapScript(script: String): String =
            "(function(){try{\n$script\n}catch(e){AndroidBridgeNative.reportError((e&&e.message)||String(e));}})();"

        /** 桥垫片:定义 AndroidBridge / AndroidBridgePromise / shiguangBridge* / __resolve。 */
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
              function report(message){
                try { AndroidBridgeNative.reportError(String(message)); } catch(e) {}
              }
              window.onerror = function(message){ report(message); };
              window.addEventListener('unhandledrejection', function(ev){
                var r = ev && ev.reason;
                report(r && r.message ? r.message : r);
              });
              function wrap(method){
                return function(){
                  var args = Array.prototype.slice.call(arguments);
                  return new Promise(function(resolve){
                    var id = 'cb_' + (Math.random() * 1e9 | 0);
                    // 超时兜底:页面切换 / 原生未回调时不再永久挂起(60s)
                    var timer = setTimeout(function(){
                      if (window.__resolvers[id]) {
                        delete window.__resolvers[id];
                        resolve(null);
                      }
                    }, 60000);
                    window.__resolvers[id] = { resolve: resolve, timer: timer };
                    args.push(id);
                    try { AndroidBridgeNative[method].apply(null, args); }
                    catch(e) { delete window.__resolvers[id]; clearTimeout(timer); resolve(null); }
                  });
                };
              }
              window.AndroidBridge = {
                showToast: function(m){ AndroidBridgeNative.showToast(String(m)); },
                notifyTaskCompletion: function(){ AndroidBridgeNative.notifyTaskCompletion(); }
              };
              window.AndroidBridgePromise = {
                showAlert: wrap('showAlert'),
                showPrompt: wrap('showPrompt'),
                showSingleSelection: wrap('showSingleSelection'),
                saveImportedCourses: wrap('saveImportedCourses'),
                savePresetTimeSlots: wrap('savePresetTimeSlots'),
                saveCourseConfig: wrap('saveCourseConfig')
              };
              window.shiguangBridge = window.AndroidBridge;
              window.shiguangBridgePromise = window.AndroidBridgePromise;
            })();
        """
    }
}

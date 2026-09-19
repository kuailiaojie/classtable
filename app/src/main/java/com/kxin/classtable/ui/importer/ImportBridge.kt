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
import org.json.JSONTokener

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

    /**
     * 正在执行适配脚本的那个 WebView。所有弹窗回调与 Promise 解析都发到这里。
     *
     * 官方实现同样在开始导入时绑定(`EduImportBrowserUi.runOriginalImportScript`:
     * `bridge.bindWebView(target)` 之后才 `evaluateJavascript(script)`)。
     *
     * 为什么必须绑定:主页面与 window.open 出来的弹窗页是**两个独立的 JS 文档**,
     * 各自的 `__resolvers` 相互隔离。脚本注入在哪个文档,Promise 的解析器就在哪个文档;
     * 若把 resolve 发到另一个 WebView,解析器找不到 → Promise 一直挂到 60 秒超时 →
     * 适配器拿到 null → 判定「用户取消」→ 提示「已取消导入」。
     */
    @Volatile
    private var bound: WebView? = null

    fun bindWebView(webView: WebView?) {
        bound = webView
    }

    private fun target(): WebView = bound ?: webViewProvider()

    @JavascriptInterface
    fun showToast(message: String) {
        mainHandler.post { onToast(message ?: "") }
    }

    @JavascriptInterface
    fun notifyTaskCompletion() {
        mainHandler.post { onDone() }
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
            AlertDialog.Builder(target().context)
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
            showPromptDialog(target(), title, message, defaultText, validator, callbackId)
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
                    validate(webView, validator, value) { error ->
                        if (error == null) {
                            resolve(callbackId, JSONObject.quote(value))
                        } else {
                            // 重弹时把适配器给出的错误文案显示出来(它返回文案就是为了展示)
                            showPromptDialog(webView, title, error, value, validator, callbackId)
                        }
                    }
                }
            }
            .setNegativeButton("取消") { _, _ -> resolve(callbackId, "null") }
            .setOnCancelListener { resolve(callbackId, "null") }
            .show()
    }

    /**
     * 调用适配器页面里的校验函数(showPrompt 的第 4 个参数,如 validateYearInput)。
     *
     * 适配器统一遵循拾光 Bridge 规范:**校验通过返回 `false`,不通过返回错误文案**。
     * 依据见 BTBU 的注释「规范要求:验证通过返回 false,失败返回错误文案」,以及
     * GXDLXY / HNVCC / CCIT / CQRK / CQIE 等脚本里 validate* 函数的实现。
     *
     * 之前这里判反了(把 `false` 当失败):输入**合法**反而判定失败 → 反复重弹 →
     * 用户只能点取消 → 脚本拿到 null → 「已取消导入」,也就是用户永远导入不成功。
     * 现在判定与官方 `validateEduBridgePrompt` 完全一致。
     */
    private fun validate(webView: WebView, validator: String, value: String, onResult: (String?) -> Unit) {
        // 函数名直接内联成调用(与官方一致),因此必须是合法的标识符/属性路径,
        // 否则一律放行,避免把适配器传来的字符串拼进脚本。
        if (!ValidatorName.matches(validator)) {
            onResult(null)
            return
        }
        val js = VALIDATE_JS
            .replace("__NAME__", validator)
            .replace("__VALUE__", JSONObject.quote(value))
        webView.post {
            runCatching {
                webView.evaluateJavascript(js) { result ->
                    val error = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
                    mainHandler.post { onResult(error?.takeIf { it.isNotBlank() }) }
                }
            }.onFailure { mainHandler.post { onResult(null) } }
        }
    }

    private val ValidatorName = Regex("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$")

    /** 单选列表:defaultIndex 预选,取消返回 null;无可选项时直接返回 null(与官方一致)。 */
    @JavascriptInterface
    fun showSingleSelection(title: String, itemsJson: String, defaultIndex: Int, callbackId: String) {
        mainHandler.post {
            val items = runCatching {
                val arr = JSONArray(itemsJson ?: "[]")
                (0 until arr.length()).map { arr.optString(it) }
            }.getOrDefault(emptyList())
            // 官方:EduSchoolSelectionUi 里 options 为空 → 弹「没有可选项」并 resolve null。
            // 这里不返回任何序号:适配器普遍会校验 `idx >= list.length`(如 HPU、WUST),
            // 返回一个不存在的序号会被判成「已取消导入」。
            if (items.isEmpty()) {
                resolve(callbackId, "null")
                return@post
            }
            val checked = defaultIndex.coerceIn(items.indices)
            var selected = checked
            AlertDialog.Builder(target().context)
                .setTitle(title ?: "")
                .setSingleChoiceItems(items.toTypedArray(), checked) { _, which -> selected = which }
                .setPositiveButton("确定") { _, _ -> resolve(callbackId, selected.toString()) }
                .setNegativeButton("取消") { _, _ -> resolve(callbackId, "null") }
                .setOnCancelListener { resolve(callbackId, "null") }
                .show()
        }
    }

    private fun resolve(callbackId: String, jsValue: String) {
        val webView = target()
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

        /**
         * 把「垫片 + 适配脚本」拼成一次注入的脚本。
         *
         * 适配器脚本必须**原样执行,不能包 IIFE**:适配器普遍用裸函数声明写校验函数
         * (全库 72 个,如 NEU 的 `function validateYearInput(input){...}`),而 showPrompt 的
         * 校验按名字在**全局作用域**求值 —— 一旦被包进函数作用域就全部 `is not defined`,
         * 校验永远不通过,输入框反复重弹,用户只能点取消,脚本拿到 null 后提示「已取消导入」
         * (77 个带校验的 prompt 调用点里有 62 个中招)。
         *
         * 官方也是原样执行:`ShiguangWarehouse.resolveScript` 只读文件,
         * `EduImportBrowserUi.runOriginalImportScript` 直接 `evaluateJavascript(script, null)`,
         * 因此顶层 `function` / `var` / `const` / `let` 都能被后续脚本看到。
         */
        fun buildInjection(script: String): String = SHIM_JS + "\n" + script

        /**
         * showPrompt 校验函数的调用脚本:`__NAME__` 换成函数名、`__VALUE__` 换成用户输入。
         *
         * 判定与官方逐字一致(`EduSchoolSelectionUi.validateEduBridgePrompt`):
         * `false` / `null` / `undefined` / 空串 → **通过**(返回 null);
         * 其它值 **String() 后作为错误文案**;校验函数抛异常时把异常信息当错误文案。
         *
         * 这条契约此前写反过(把 `false` 当失败),会让合法输入被反复重弹、用户只能取消,
         * 因此抽成常量并单独测试。
         */
        const val VALIDATE_JS = """
            (function(){
              try {
                var r = __NAME__(__VALUE__);
                if (r === false || r === null || r === undefined || r === '') return null;
                return String(r);
              } catch (error) {
                return (error && error.message) ? String(error.message) : String(error);
              }
            })()
        """

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
              // 幂等:官方垫片同样有 `if (window._shiguangBridgeInjected) return;`。
              // __resolvers 是「进行中的 Promise」的唯一索引,重复执行会把正在等待的
              // 弹窗回调一起清掉 —— 那个 Promise 再也无法 resolve,只能挂到 60 秒超时
              // 返回 null,适配器就会误判成「用户取消」。我们在落地与 onPageFinished
              // 各注入一次,所以这个守卫是必需的。
              if (window.__shiguangShimReady) { return; }
              window.__shiguangShimReady = true;
              if (!window.__resolvers) { window.__resolvers = {}; }
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

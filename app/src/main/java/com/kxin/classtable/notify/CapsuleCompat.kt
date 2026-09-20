package com.kxin.classtable.notify

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import com.kxin.classtable.R
import org.json.JSONObject

/**
 * 状态栏胶囊(实时活动)兼容层。
 *
 * 荣耀「灵动胶囊」、小米「超级岛」、OPPO「实况通知」这类国产胶囊都以 **Android 16 的
 * Live Updates 规范**为基础,系统只提升满足全部条件的通知:
 *
 * 1. 清单里声明 `POST_PROMOTED_NOTIFICATIONS`(非运行时权限,声明即可;用户可在系统设置里关掉);
 * 2. 通知本身 `ongoing`、有 `contentTitle`、使用受支持的样式(BigTextStyle / ProgressStyle 等),
 *    且**不能**用自定义 RemoteViews、不能 colorized、不能是分组摘要、渠道不能是 IMPORTANCE_MIN;
 * 3. 主动请求提升(`setRequestPromotedOngoing(true)` / `EXTRA_REQUEST_PROMOTED_ONGOING`),
 *    并用 `setShortCriticalText` 提供胶囊里的短文案(**纯文本**,状态栏胶囊宽度上限 96dp,
 *    文字放不下就只剩图标,所以短文案要尽量 ≤7 字)。
 *
 * 任何一条不满足系统都不会放进胶囊 —— 这也是「装到手机上不出胶囊」最常见的原因。
 * 小米(澎湃 OS)另外提供 `miui.focus.param` 扩展参数作为第三方上岛的公开途径,见 [XiaomiIsland]。
 */
object CapsuleCompat {
    private const val TAG = "CapsuleCompat"

    /** 系统当前是否允许本应用发布被提升的通知(API 36+);更早的系统返回 null 表示不适用。 */
    fun canPostPromoted(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 36) return null
        return runCatching {
            context.getSystemService(android.app.NotificationManager::class.java)
                ?.canPostPromotedNotifications()
        }.getOrNull()
    }

    /** 跳系统「实时活动 / 提升通知」设置页;系统没有该页面时退回应用通知设置页。 */
    fun promotedSettingsIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= 36) {
            val promoted = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolvable = runCatching {
                context.packageManager.resolveActivity(
                    promoted,
                    PackageManager.MATCH_DEFAULT_ONLY,
                ) != null
            }.getOrDefault(false)
            if (resolvable) return promoted
        }
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * 请求把通知提升为实时活动,并设置胶囊短文案。
     *
     * 这两件事在 API 36 的公开 SDK 里没有对应的 `EXTRA_*` 常量(`setRequestPromotedOngoing`
     * 甚至没有导出方法),所以以规范里的 extras 键为准写入;方法存在时再补一次调用,
     * 签名在不同版本上有 `String` / `CharSequence` 两种,都试。
     */
    fun requestPromotion(builder: Notification.Builder, shortText: String) {
        builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        runCatching {
            builder.javaClass
                .getMethod("setRequestPromotedOngoing", java.lang.Boolean.TYPE)
                .invoke(builder, true)
        }.onFailure { Log.d(TAG, "setRequestPromotedOngoing 不可用(${it.javaClass.simpleName}),以 extras 提交提升请求") }

        val called = runCatching {
            if (Build.VERSION.SDK_INT >= 36) builder.setShortCriticalText(shortText) else null
        }.isSuccess
        val reflected = called || runCatching {
            builder.javaClass
                .getMethod("setShortCriticalText", String::class.java)
                .invoke(builder, shortText)
        }.recoverCatching {
            builder.javaClass
                .getMethod("setShortCriticalText", CharSequence::class.java)
                .invoke(builder, shortText)
        }.isSuccess
        if (!reflected) Log.d(TAG, "setShortCriticalText 不可用,以 extras 提交胶囊文案")
        // 胶囊文案保持纯文本:部分机型的渲染器会丢弃带 span 的短文案
        builder.extras.putCharSequence(EXTRA_SHORT_CRITICAL_TEXT, shortText)
    }

    /**
     * 构建后的自检日志:`promotable` = 系统认为是否具备提升条件(不含用户开关),
     * `requested` = 我们是否请求了提升。装到真机上先看这两项,比猜胶囊为什么不出现快得多。
     */
    fun logPromotionState(context: Context, notification: Notification) {
        val promotable = runCatching {
            notification.javaClass.getMethod("hasPromotableCharacteristics")
                .invoke(notification) as? Boolean
        }.getOrNull()
        val requested = notification.extras.getBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, false)
        Log.i(
            TAG,
            "实时活动构建:promotable=$promotable, requested=$requested, " +
                "canPostPromoted=${canPostPromoted(context)}, chip=${
                    notification.extras.getCharSequence(EXTRA_SHORT_CRITICAL_TEXT)
                }, 小米岛=${XiaomiIsland.state(context).summary()}",
        )
    }

    const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"
}

/**
 * 小米澎湃 OS「超级岛 / 焦点通知」。
 *
 * 官方《超级岛开发指南》给了客户端接入方式:在原生通知的 extras 里放一个
 * `miui.focus.param` JSON(`param_v2` 下含交互能力、摘要态、焦点通知数据),并可用
 * `miui.focus.pics` 提供图片、`miui.focus.actions` 提供按钮。
 *
 * 这里只在**确认设备支持**时才补参数(三件事都查:是否小米系、岛能力开关、焦点通知协议
 * 版本与权限),任何一项不满足就完全不动通知 —— 也就是退回普通通知,不会因为参数不识别
 * 而影响提醒送达。参数里显式写入 `filterWhenNoPermission=false`,权限被关时同样退化为普通通知。
 */
object XiaomiIsland {
    private const val TAG = "XiaomiIsland"
    private const val PIC_ICON = "miui.focus.pic_icon"

    /** 小米/红米/POCO。 */
    private fun isXiaomiFamily(): Boolean {
        val brand = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        return brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")
    }

    data class State(
        val family: Boolean,
        val islandSupported: Boolean,
        val protocolVersion: Int,
        val focusAllowed: Boolean,
    ) {
        /** 三项都满足才值得补参数:支持岛、协议到 OS3(3 才有岛模板)、焦点通知权限已开。 */
        val usable: Boolean get() = family && islandSupported && protocolVersion >= 3 && focusAllowed

        fun summary(): String =
            if (!family) "非小米设备" else "支持=$islandSupported/协议=$protocolVersion/权限=$focusAllowed"
    }

    @Volatile
    private var cached: State? = null

    /** 查询结果进程内缓存(查询里有跨进程调用,不适合每次构建通知都做)。 */
    fun state(context: Context): State = cached ?: readState(context).also { cached = it }

    fun invalidate() {
        cached = null
    }

    private fun readState(context: Context): State {
        if (!isXiaomiFamily()) return State(false, false, 0, false)
        // 1. 系统是否支持岛:反射读 SystemProperties.persist.sys.feature.island
        val islandSupported = runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val getBoolean = clazz.getDeclaredMethod(
                "getBoolean",
                String::class.java,
                java.lang.Boolean.TYPE,
            )
            getBoolean.invoke(null, "persist.sys.feature.island", false) as? Boolean
        }.getOrNull() ?: false
        // 2. 焦点通知协议版本:1=OS1 2=OS2 3=OS3(只有 3 支持超级岛模板)
        val protocol = runCatching {
            Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
        }.getOrDefault(0)
        // 3. 本应用是否被允许发焦点通知
        val focusAllowed = runCatching {
            val extras = Bundle().apply { putString("package", context.packageName) }
            context.contentResolver
                .call(Uri.parse("content://miui.statusbar.notification.public"), "canShowFocus", null, extras)
                ?.getBoolean("canShowFocus", false)
        }.getOrNull() ?: false
        return State(true, islandSupported, protocol, focusAllowed)
    }

    /**
     * 给实时活动通知补上超级岛参数。返回是否写入成功。
     *
     * @param chipText 胶囊/小岛上的短文案(纯文本,如「10分钟」)
     */
    fun applyTo(
        builder: Notification.Builder,
        context: Context,
        courseName: String,
        placeText: String,
        statusLabel: String,
        chipText: String,
        timeoutMinutes: Int,
    ): Boolean {
        if (!state(context).usable) return false
        return runCatching {
            val params = JSONObject().put(
                "param_v2",
                JSONObject()
                    .put("protocol", 1)
                    .put("business", "course")
                    .put("updatable", true)
                    .put("enableFloat", false)
                    .put("timeout", timeoutMinutes.coerceIn(1, 720))
                    // 焦点通知权限被关掉时退化为普通通知,而不是把通知整个过滤掉
                    .put("filterWhenNoPermission", false)
                    .put("ticker", chipText)
                    .put("aodTitle", "$courseName · $chipText")
                    .put(
                        "param_island",
                        JSONObject()
                            .put("islandProperty", 1)
                            .put(
                                "bigIslandArea",
                                JSONObject()
                                    .put(
                                        "imageTextInfoLeft",
                                        JSONObject()
                                            .put("type", 1)
                                            .put(
                                                "picInfo",
                                                JSONObject().put("type", 1).put("pic", PIC_ICON),
                                            )
                                            .put(
                                                "textInfo",
                                                JSONObject()
                                                    .put("frontTitle", statusLabel)
                                                    .put("title", courseName)
                                                    .put("content", placeText)
                                                    .put("useHighLight", false),
                                            ),
                                    )
                                    .put("picInfo", JSONObject().put("type", 1).put("pic", PIC_ICON)),
                            )
                            .put(
                                "smallIslandArea",
                                JSONObject().put(
                                    "picInfo",
                                    JSONObject().put("type", 1).put("pic", PIC_ICON),
                                ),
                            ),
                    )
                    .put(
                        "baseInfo",
                        JSONObject()
                            .put("title", courseName)
                            .put("content", statusLabel)
                            .put("type", 2),
                    )
                    .put(
                        "hintInfo",
                        JSONObject().put("type", 1).put("title", chipText),
                    ),
            )
            builder.extras.putString("miui.focus.param", params.toString())
            builder.extras.putBundle(
                "miui.focus.pics",
                Bundle().apply {
                    putParcelable(PIC_ICON, Icon.createWithResource(context, R.mipmap.ic_launcher))
                },
            )
            Log.d(TAG, "已补超级岛参数(${state(context).summary()})")
            true
        }.onFailure { Log.w(TAG, "超级岛参数构建失败,退回普通通知: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
    }
}

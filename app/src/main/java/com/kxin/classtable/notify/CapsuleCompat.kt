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
 * 3. 主动请求提升(`setRequestPromotedOngoing(true)` / `EXTRA_REQUEST_PROMOTED_ONGOING`)。
 *    胶囊那一格的倒计时交给系统的 `when` + Chronometer 自己走(通知上写死目标时刻),
 *    我们不写 `android.shortCriticalText` —— 它优先级高于计时器,写上就只能靠重发刷新。
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
     * 请求把通知提升为实时活动。
     *
     * API 36 的公开 SDK 里 `setRequestPromotedOngoing` 没有导出方法,所以以规范里的 extras
     * 键为准写入;方法存在时再补一次调用。
     */
    fun requestPromotion(builder: Notification.Builder) {
        builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        runCatching {
            builder.javaClass
                .getMethod("setRequestPromotedOngoing", java.lang.Boolean.TYPE)
                .invoke(builder, true)
        }.onFailure { Log.d(TAG, "setRequestPromotedOngoing 不可用(${it.javaClass.simpleName}),以 extras 提交提升请求") }
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
                "canPostPromoted=${canPostPromoted(context)}, 小米岛=${XiaomiIsland.state(context).summary()}",
        )
    }

    const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
}

/**
 * 小米澎湃 OS「超级岛 / 焦点通知」。
 *
 * 官方《超级岛开发指南》给了客户端接入方式:在原生通知的 extras 里放一个 `miui.focus.param`
 * JSON(`param_v2` 下含交互能力、摘要态、焦点通知数据),并可用 `miui.focus.pics` 提供图片。
 *
 * 这里只在**确认设备支持**时才补参数(设备是小米系、系统开了岛能力、焦点通知协议版本够、
 * 且本应用被允许发焦点通知),任何一项不满足就完全不动通知 —— 退回普通通知,不会因为参数
 * 不被识别而影响提醒送达。参数里显式写入 `filterWhenNoPermission=false`,权限被关时同样退化。
 */
object XiaomiIsland {
    private const val TAG = "XiaomiIsland"

    /** 小米岛复用同一份短文案:胶囊那一格与锁屏短行都取它。 */
    private const val FocusParameter = "miui.focus.param"
    private const val AppIconPicture = "miui.focus.pic_app_icon"
    private const val AppIconDarkPicture = "miui.focus.pic_app_icon_dark"
    private const val SmallPicture = "miui.focus.pic_small"
    private const val SmallPictureDark = "miui.focus.pic_small_dark"

    /** 小米/红米/POCO。 */
    fun isXiaomiFamily(): Boolean {
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
     * 给构建好的通知补上超级岛参数与图片。设备不支持时是空操作。
     *
     * @param islandTitle 超级岛标题(课前给地点,课中 / 课后给状态)
     */
    fun decorate(
        context: Context,
        notification: Notification,
        payload: LiveUpdate,
        status: LiveUpdateStatus,
        islandTitle: String,
    ) {
        if (!state(context).usable) return
        runCatching {
            notification.extras.putString(
                FocusParameter,
                parameters(payload, status, islandTitle, System.currentTimeMillis(), context.packageName),
            )
            val icon = Icon.createWithResource(context, R.mipmap.ic_launcher)
            notification.extras.putBundle(
                "miui.focus.pics",
                Bundle().apply {
                    putParcelable(AppIconPicture, icon)
                    putParcelable(AppIconDarkPicture, icon)
                    putParcelable(SmallPicture, icon)
                    putParcelable(SmallPictureDark, icon)
                },
            )
            Log.d(TAG, "已补超级岛参数(${state(context).summary()})")
        }.onFailure { Log.w(TAG, "超级岛参数构建失败,退回普通通知: ${it.javaClass.simpleName}") }
    }

    internal fun parameters(
        payload: LiveUpdate,
        status: LiveUpdateStatus,
        islandTitle: String,
        nowMillis: Long = System.currentTimeMillis(),
        packageName: String = "com.kxin.classtable",
    ): String {
        val beforeClass = status.phase == LiveUpdatePhase.BEFORE_CLASS
        val timerAt = status.nextTransitionAtMillis?.takeIf { beforeClass && it > nowMillis }
        val courseName = payload.name.ifBlank { islandTitle }
        val placeText = payload.location.ifBlank { "未设置地点" }
        val islandStatus = when (status.phase) {
            LiveUpdatePhase.BEFORE_CLASS -> placeText
            LiveUpdatePhase.IN_CLASS -> "已上课"
            LiveUpdatePhase.BREAK -> "课间"
            LiveUpdatePhase.FINISHED -> "已下课"
        }
        // 小岛左侧固定显示课名;右侧课前给地点,课中 / 课后给状态
        val left = JSONObject().put("type", 1).put(
            "textInfo",
            JSONObject()
                .put("title", courseName)
                .put("content", "")
                .put("showHighlightColor", false)
                .put("narrowFont", false),
        )
        val bigIsland = JSONObject()
            .put("templateNo", 2)
            .put("imageTextInfoLeft", left)
            .put(
                "textInfo",
                JSONObject()
                    .put("frontTitle", "")
                    .put("title", if (beforeClass) islandTitle else islandStatus)
                    .put("content", "")
                    .put("showHighlightColor", false)
                    .put("narrowFont", false),
            )
        val island = JSONObject()
            .put("islandProperty", 1)
            .put("islandTimeout", 3600)
            .put("bigIslandArea", bigIsland)
            .put(
                "smallIslandArea",
                JSONObject().put(
                    "picInfo",
                    JSONObject()
                        .put("type", 1)
                        .put("pic", SmallPicture)
                        .put("picDark", SmallPictureDark),
                ),
            )
        val detail = listOf(payload.timeText, payload.location)
            .filter(String::isNotBlank)
            .joinToString(" · ")
        val card = JSONObject()
            .put("type", 2)
            .put("title", courseName)
            .put("content", detail)
            .put("subTitle", "")
            .put("extraTitle", "")
            .put("specialTitle", "")
            .put("subContent", "")
            .put("picFunction", "")
            .put("showDivider", true)
            .put("showContentDivider", false)
            .put("colorTitle", "#111111")
            .put("colorTitleDark", "#ffffff")
            .put("colorContent", "#333333")
            .put("colorContentDark", "#cccccc")
        val hint = JSONObject()
            .put("type", 2)
            .put("content", if (beforeClass) status.statusText else "现在")
            .put("title", if (beforeClass) "" else islandStatus)
            .put("subContent", "地点")
            .put("subTitle", placeText)
            .put("colorContent", "#666666")
            .put("colorContentDark", "#aaaaaa")
            .put("colorTitle", "#222222")
            .put("colorTitleDark", "#eeeeee")
            .put("colorSubContent", "#666666")
            .put("colorSubContentDark", "#aaaaaa")
            .put("colorSubTitle", "#222222")
            .put("colorSubTitleDark", "#eeeeee")
            .put(
                "actionInfo",
                JSONObject()
                    .put("actionTitle", "查看课表")
                    .put("actionIntentType", 1)
                    .put("actionIntent", "intent:#Intent;component=$packageName/.MainActivity;end"),
            )
            .put("timerInfo", timerInfo(timerAt, nowMillis))
        return JSONObject()
            .put(
                "param_v2",
                JSONObject()
                    .put("protocol", 1)
                    .put("business", "course_reminder")
                    .put("enableFloat", beforeClass)
                    .put("islandFirstFloat", !beforeClass)
                    .put("updatable", true)
                    .put("outEffectSrc", "outer_glow")
                    .put("aodTitle", placeText)
                    .put("reopen", "reopen")
                    // 焦点通知权限被关掉时退化为普通通知,而不是把通知整个过滤掉
                    .put("filterWhenNoPermission", false)
                    .put("baseInfo", card)
                    .put("picInfo", JSONObject().put("type", 1).put("pic", ""))
                    .put("hintInfo", hint)
                    .put("param_island", island),
            )
            .toString()
    }

    private fun timerInfo(timerAt: Long?, nowMillis: Long): JSONObject = JSONObject().apply {
        put("timerType", if (timerAt != null) -1 else 0)
        put("timerWhen", timerAt ?: 0L)
        put("timerTotal", 0L)
        put("timerSystemCurrent", if (timerAt != null) nowMillis else 0L)
    }
}

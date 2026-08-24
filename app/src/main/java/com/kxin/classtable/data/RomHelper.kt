package com.kxin.classtable.data

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** 国产 ROM 类型(用于后台保护引导)。 */
enum class RomType(val label: String) {
    MIUI("小米/Redmi"),
    COLOROS("OPPO/一加"),
    ORIGINOS("vivo/iQOO"),
    EMUI("华为"),
    MAGICOS("荣耀"),
    FLYME("魅族"),
    STOCK("原生/其他"),
}

/**
 * 国产 ROM 后台保护辅助:
 * 识别 ROM → 提供自启动/后台管理页跳转;检测通知 / 精确闹钟 / 电池优化白名单状态。
 * 背景:课程提醒依赖精确闹钟,国产 ROM 的电池优化与自启动限制会拦截闹钟、拦截开机广播,
 * 必须引导用户放行,否则提醒会"悄悄丢"。
 */
object RomHelper {

    fun detect(): RomType {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            "xiaomi" in manufacturer || "redmi" in manufacturer || "poco" in manufacturer -> RomType.MIUI
            "oppo" in manufacturer || "oneplus" in manufacturer || "realme" in manufacturer -> RomType.COLOROS
            "vivo" in manufacturer || "iqoo" in manufacturer -> RomType.ORIGINOS
            "huawei" in manufacturer -> RomType.EMUI
            "honor" in manufacturer -> RomType.MAGICOS
            "meizu" in manufacturer -> RomType.FLYME
            else -> RomType.STOCK
        }
    }

    /** 通知权限是否已授予(Android 13+ 需运行时授权)。 */
    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 精确闹钟权限(Android 12+ 需用户授予,否则降级 setWindow ±1 分钟)。 */
    fun exactAlarmGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    /** 是否在电池优化白名单内。 */
    fun ignoreBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 精确闹钟权限引导(未授权时返回可跳转的 Intent,已授权返回 null)。 */
    fun exactAlarmSettingsIntent(context: Context): Intent? {
        if (exactAlarmGranted(context)) return null
        return if (Build.VERSION.SDK_INT >= 31) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        } else {
            null
        }
    }

    /** 电池优化白名单引导(API 30+ 跳白名单列表,低版本直接弹请求)。 */
    fun batteryOptimizationIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        }

    /** 应用详情页(兜底引导)。 */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** 各 ROM 自启动/后台管理页;逐 target 尝试打开,全部失败返回 false(调用方兜底到应用详情页)。 */
    fun tryOpenAutoStart(context: Context): Boolean {
        val targets = when (detect()) {
            RomType.MIUI -> listOf(
                "miui.intent.action.OP_AUTO_START" to null,
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
            RomType.COLOROS -> listOf(
                "com.coloros.safecenter.permission.startup" to null,
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            )
            RomType.ORIGINOS -> listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            )
            RomType.EMUI -> listOf(
                "com.huawei.systemmanager" to "com.huawei.permissionmanager.ui.MainActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            )
            RomType.MAGICOS -> listOf(
                "com.hihonor.systemmanager" to "com.hihonor.permissionmanager.ui.MainActivity",
            )
            RomType.FLYME -> listOf(
                "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity",
            )
            RomType.STOCK -> emptyList()
        }
        for ((pkg, activity) in targets) {
            val intent = if (activity != null) {
                Intent().setComponent(ComponentName(pkg, activity))
            } else {
                Intent(pkg)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
                // 该 target 不存在,尝试下一个
            }
        }
        return false
    }
}

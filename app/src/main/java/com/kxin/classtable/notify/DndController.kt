package com.kxin.classtable.notify

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 上课自动免打扰:在课程时间段内把系统切进免打扰,课时结束再退回。
 *
 * 用系统「勿扰访问权限」([NotificationManager.setInterruptionFilter])而不是
 * AutomaticZenRule:本功能要的只是「这段时间别响」,不需要系统级的勿扰规则列表;
 * 代价是必须由用户在系统设置里授予 [Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS]。
 *
 * 只动我们自己的那一次切换:**进入时手机本来就已经是免打扰就不接管**(退出时自然也
 * 不会去关掉用户自己开的免打扰);接管时记下原来的过滤档,退出时按原样恢复。
 */
object DndController {

    private const val PREFS = "class_dnd"
    private const val KEY_MANAGED = "managed"
    private const val KEY_SAVED_FILTER = "saved_filter"

    private const val FILTER_ALL = NotificationManager.INTERRUPTION_FILTER_ALL
    private const val FILTER_NONE = NotificationManager.INTERRUPTION_FILTER_NONE

    private fun manager(context: Context): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    /** 勿扰访问权限是否已授予(用户可在系统设置里随时收回)。 */
    fun isGranted(context: Context): Boolean = runCatching {
        manager(context)?.isNotificationPolicyAccessGranted == true
    }.getOrDefault(false)

    /** 授权引导页:系统「勿扰访问权限」列表。 */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    /**
     * 进入免打扰。返回是否处于免打扰(未授权或设置失败时为 false)。
     * 本来就已经是免打扰时不做任何改动 —— 那档是用户自己的,不该由我们接管。
     */
    fun enter(context: Context): Boolean {
        val nm = manager(context) ?: return false
        if (!isGranted(context)) return false
        val current = runCatching { nm.currentInterruptionFilter }.getOrDefault(FILTER_ALL)
        if (current == FILTER_NONE) return true
        prefs(context).edit()
            .putInt(KEY_SAVED_FILTER, current)
            .putBoolean(KEY_MANAGED, true)
            .apply()
        return runCatching { nm.setInterruptionFilter(FILTER_NONE) }.isSuccess
    }

    /** 退出免打扰,恢复到进入前的档位。我们没接管过(或已退出)时为无操作。 */
    fun exit(context: Context) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_MANAGED, false)) return
        val saved = prefs.getInt(KEY_SAVED_FILTER, FILTER_ALL)
        prefs.edit().remove(KEY_MANAGED).remove(KEY_SAVED_FILTER).apply()
        if (!isGranted(context)) return
        runCatching { manager(context)?.setInterruptionFilter(saved) }
    }

    /**
     * 忘掉接管状态(不动系统当前档位)。
     * 重启后系统的免打扰本就是默认档,旧标记若留着,一次迟到的「下课」闹钟会把早已过期的
     * 档位再写回去,所以开机时先清掉。
     */
    fun forget(context: Context) {
        prefs(context).edit().remove(KEY_MANAGED).remove(KEY_SAVED_FILTER).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

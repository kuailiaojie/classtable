package com.kxin.classtable.data

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Firebase Analytics 轻封装:init 一次,之后 log / setUserId / setUserProperty 即可;
 * 任何失败静默,不影响功能。
 *
 * 约定:事件名与参数一律 snake_case;不发送可识别个人的信息(如明文邮箱)。
 */
object Analytics {
    @Volatile
    private var instance: FirebaseAnalytics? = null

    fun init(context: Context) {
        instance = runCatching { FirebaseAnalytics.getInstance(context.applicationContext) }.getOrNull()
    }

    fun log(name: String, vararg params: Pair<String, Any>) {
        val analytics = instance ?: return
        runCatching {
            val bundle = Bundle()
            params.forEach { (k, v) -> putParam(bundle, k, v) }
            analytics.logEvent(name, bundle)
        }
    }

    /** 登录后把后续事件绑到 uid;登出传 null 清空。 */
    fun setUserId(uid: String?) {
        instance?.let { runCatching { it.setUserId(uid) } }
    }

    /** 用户属性(主题 / 通知形态 / ROM 等),用于分段分析;值为 null 时清除该属性。 */
    fun setUserProperty(name: String, value: String?) {
        instance?.let { runCatching { it.setUserProperty(name, value) } }
    }

    /** 屏幕追踪:根标签用路由名(week / courses / agenda / settings),二级页用其路由模式。 */
    fun screenView(route: String) {
        val analytics = instance ?: return
        runCatching {
            val bundle = Bundle().apply {
                putString(FirebaseAnalytics.Param.SCREEN_NAME, route)
                putString(FirebaseAnalytics.Param.SCREEN_CLASS, route)
            }
            analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, bundle)
        }
    }

    private fun putParam(bundle: Bundle, key: String, value: Any) {
        when (value) {
            is String -> bundle.putString(key, value)
            is Int -> bundle.putInt(key, value)
            is Long -> bundle.putLong(key, value)
            is Double -> bundle.putDouble(key, value)
            is Boolean -> bundle.putBoolean(key, value)
        }
    }
}

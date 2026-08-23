package com.kxin.classtable.data

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/** Firebase Analytics 轻封装:init 一次,之后 log 即可;失败静默不影响功能。 */
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
            params.forEach { (k, v) ->
                when (v) {
                    is String -> bundle.putString(k, v)
                    is Int -> bundle.putInt(k, v)
                    is Long -> bundle.putLong(k, v)
                    is Double -> bundle.putDouble(k, v)
                    is Boolean -> bundle.putBoolean(k, v)
                }
            }
            analytics.logEvent(name, bundle)
        }
    }
}

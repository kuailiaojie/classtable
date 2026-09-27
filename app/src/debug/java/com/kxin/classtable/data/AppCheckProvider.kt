package com.kxin.classtable.data

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * debug 版 App Check provider:Debug provider 产出的 token 需在 Firebase 控制台登记
 * (首次运行会在 logcat 打印该 token)。release 包里没有这个类,故按 build type 分源集。
 */
internal fun installAppCheckProvider(appCheck: FirebaseAppCheck) {
    appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
}

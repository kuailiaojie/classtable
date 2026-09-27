package com.kxin.classtable.data

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/** release 版 App Check provider:Play Integrity。需要在控制台登记 release 签名的 SHA-256。 */
internal fun installAppCheckProvider(appCheck: FirebaseAppCheck) {
    appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
}

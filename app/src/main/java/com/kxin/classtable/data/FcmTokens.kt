package com.kxin.classtable.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * FCM 设备令牌同步:登录后把本机令牌写到 users/{uid}/devices/{token},
 * 服务器即可按令牌定向推送(如课程提醒)。未登录不上传。
 */
object FcmTokens {

    fun upload(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        runCatching {
            FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("devices").document(token)
                .set(
                    mapOf(
                        "token" to token,
                        "updatedAt" to System.currentTimeMillis(),
                    ),
                )
        }
    }

    fun remove(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        runCatching {
            FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("devices").document(token)
                .delete()
        }
    }
}

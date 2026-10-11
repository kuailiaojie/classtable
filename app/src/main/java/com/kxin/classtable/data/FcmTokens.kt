package com.kxin.classtable.data

import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 推送标识同步(经反代 REST):登录后把本机 Firebase Installation ID(FID)写到
 * users/{uid}/devices/{id},服务器即可按它定向推送(如课程提醒)。未登录不上传。
 *
 * FCM 已从「注册令牌」转向 FID;FID 可直接当作定向投递的 token 使用。
 */
@Singleton
class FcmTokens @Inject constructor(
    private val gateway: FirebaseGateway,
    private val authRepository: AuthRepository,
) {
    suspend fun upload(token: String) {
        val uid = authRepository.uid ?: return
        val idToken = authRepository.freshIdToken() ?: return
        runCatching {
            val path = "v1/projects/${gateway.projectId}/databases/(default)/documents/users/$uid/devices/${encodeSegment(token)}"
            gateway.firestore(
                path,
                "PATCH",
                JSONObject().put(
                    "fields",
                    JSONObject().apply {
                        put("token", JSONObject().put("stringValue", token))
                        put("updatedAt", JSONObject().put("integerValue", System.currentTimeMillis().toString()))
                    },
                ),
                idToken,
            )
        }
    }

    suspend fun remove(token: String) {
        val uid = authRepository.uid ?: return
        val idToken = authRepository.freshIdToken() ?: return
        runCatching {
            val path = "v1/projects/${gateway.projectId}/databases/(default)/documents/users/$uid/devices/${encodeSegment(token)}"
            gateway.firestore(path, "DELETE", null, idToken)
        }
    }
}

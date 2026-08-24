package com.kxin.classtable.data

/** 登录会话(REST 版认证)。idToken 短期有效,refreshToken 长期,均存加密存储。 */
data class AuthSession(
    val uid: String,
    val email: String?,
    val idToken: String,
    val refreshToken: String,
    /** idToken 过期时刻(epoch millis),由响应 expiresIn 秒推算。 */
    val expiresAt: Long,
)

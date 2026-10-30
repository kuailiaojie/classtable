package com.kxin.classtable.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 轻量加密偏好存储:值用 Android Keystore 里的 AES-GCM 密钥加密后落盘。
 *
 * 用来替代已废弃的 `androidx.security:security-crypto`(EncryptedSharedPreferences)——
 * 那个库整套 API 自 1.1.0 起被弃用,且官方没给 drop-in 替代,建议改用平台 Keystore。
 * 这里只做最要紧的一件事:把**敏感的值**加密。键名是固定字面量、本身不敏感,明文保存。
 *
 * 解密失败(密钥失效 / 密文损坏 / ROM 升级后 Keystore 重置)一律返回 null,调用方退化为
 * 「没有这份数据」,不会抛异常 —— 会话类数据丢了重新登录即可,不能因此崩在启动路径上。
 */
internal class SecurePrefs(context: Context, fileName: String) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    @Volatile
    private var cachedKey: SecretKey? = null

    fun getString(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return decrypt(stored)
    }

    fun putString(key: String, value: String) {
        val encrypted = encrypt(value) ?: return
        prefs.edit().putString(key, encrypted).apply()
    }

    fun getLong(key: String, default: Long): Long = getString(key)?.toLongOrNull() ?: default

    fun putLong(key: String, value: Long) = putString(key, value.toString())

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encrypt(plain: String): String? {
        return runCatching {
            performEncrypt(plain, getSecretKey())
        }.recoverCatching {
            // 捕获 KeyPermanentlyInvalidatedException / KeyStoreException,重新生成密钥并重试一次自愈
            performEncrypt(plain, getSecretKey(forceRecreate = true))
        }.getOrNull()
    }

    private fun performEncrypt(plain: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // `iv:密文` —— IV 每次由 Cipher 随机生成,必须与密文一起存下来才能解。
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val separator = stored.indexOf(':')
        require(separator > 0) { "malformed secure value" }
        val iv = Base64.decode(stored.substring(0, separator), Base64.NO_WRAP)
        val ciphertext = Base64.decode(stored.substring(separator + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    /** 取 Keystore 里的密钥;没有或损坏时用 AES-256-GCM 重新生成(别名固定,全应用共用)。 */
    @Synchronized
    private fun getSecretKey(forceRecreate: Boolean = false): SecretKey {
        if (!forceRecreate && cachedKey != null) {
            return cachedKey!!
        }
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (forceRecreate && keyStore.containsAlias(KEY_ALIAS)) {
            runCatching { keyStore.deleteEntry(KEY_ALIAS) }
        }
        if (!forceRecreate && keyStore.containsAlias(KEY_ALIAS)) {
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
                cachedKey = it.secretKey
                return it.secretKey
            }
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        val newKey = generator.generateKey()
        cachedKey = newKey
        return newKey
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "classtable_secure_prefs"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}

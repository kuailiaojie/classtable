package com.kxin.classtable.data

import org.json.JSONObject

/**
 * Firestore REST API `fields` 读写辅助工具。
 * 统一封装 `stringValue`、`integerValue`、`booleanValue` 结构，消除 RemoteCourse / RemoteAgenda / SyncRepository 中的重复逻辑。
 */
internal object FirestoreFields {
    fun getString(fields: JSONObject, name: String, default: String = ""): String =
        fields.optJSONObject(name)?.optString("stringValue")?.takeIf { it.isNotEmpty() } ?: default

    fun getInt(fields: JSONObject, name: String, default: Int = 0): Int =
        fields.optJSONObject(name)?.optString("integerValue")?.toIntOrNull() ?: default

    fun getIntOrNull(fields: JSONObject, name: String): Int? =
        fields.optJSONObject(name)?.optString("integerValue")?.toIntOrNull()

    fun getLong(fields: JSONObject, name: String, default: Long = 0L): Long =
        fields.optJSONObject(name)?.optString("integerValue")?.toLongOrNull() ?: default

    fun getBoolean(fields: JSONObject, name: String, default: Boolean = false): Boolean =
        fields.optJSONObject(name)?.optBoolean("booleanValue") ?: default

    fun putString(target: JSONObject, name: String, value: String): JSONObject =
        target.put(name, JSONObject().put("stringValue", value))

    fun putInt(target: JSONObject, name: String, value: Int): JSONObject =
        target.put(name, JSONObject().put("integerValue", value.toString()))

    fun putLong(target: JSONObject, name: String, value: Long): JSONObject =
        target.put(name, JSONObject().put("integerValue", value.toString()))

    fun putBoolean(target: JSONObject, name: String, value: Boolean): JSONObject =
        target.put(name, JSONObject().put("booleanValue", value))
}

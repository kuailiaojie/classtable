package com.kxin.classtable.domain

/**
 * 枚举名 → 枚举值,解析失败(或为空)时回落 [default]。
 *
 * 此前 `runCatching { X.valueOf(...) }.getOrDefault(X.Y)` 在数据层 / 设置层写了十几处,
 * 每处各带一份样板。统一成一个内联函数(内联是为了让 [enumValueOf] 拿得到具体类型)。
 */
inline fun <reified T : Enum<T>> enumOrDefault(value: String?, default: T): T =
    if (value == null) default else runCatching { enumValueOf<T>(value) }.getOrDefault(default)

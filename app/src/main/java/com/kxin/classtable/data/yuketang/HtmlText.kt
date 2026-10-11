package com.kxin.classtable.data.yuketang

/**
 * 把雨课堂公告里的 HTML 转成可读纯文本。
 *
 * 雨课堂的 `content.text` / `app_text` 有时带 HTML 标签(段落、换行、实体)。此前全链路
 * 原样透传:入库是原始串、课程详情用 Compose `Text` 渲染(纯文本)、通知也只发标题 ——
 * 于是 `<p>` / `<br>` / `&nbsp;` 会**逐字显示**在用户眼前,通知更是不可能渲染 HTML。
 *
 * 所以在**入库前**清洗一次,下游(详情页 / 通知 / 提醒里的公告摘要)自动都是干净的。
 * 纯函数,无 Android 依赖。
 */
internal object HtmlText {
    private val BREAK = Regex("(?i)<\\s*br\\s*/?\\s*>|<\\s*/\\s*(p|div|li|tr|h[1-6]|section|article|blockquote)\\s*>")
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")
    private val HORIZONTAL_WS = Regex("[ \\t\\u00A0]+")
    private val SPACE_AROUND_NEWLINE = Regex(" *\\n *")
    private val MANY_NEWLINES = Regex("\\n{3,}")

    private val NAMED_ENTITIES = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">",
        "quot" to "\"", "apos" to "'", "hellip" to "…", "mdash" to "—",
        "ndash" to "–", "middot" to "·", "ldquo" to "“", "rdquo" to "”",
        "lsquo" to "‘", "rsquo" to "’", "times" to "×", "minus" to "−",
    )

    /** HTML/实体 → 纯文本;段落与 `<br>` 变换行,空行折叠,首尾去空白。 */
    fun toPlain(raw: String): String {
        if (raw.isEmpty()) return ""
        // 没有标签也没有实体时,只做轻量 trim,避免无谓的正则开销
        if (raw.indexOf('<') < 0 && raw.indexOf('&') < 0) return raw.trim()

        var text = BREAK.replace(raw, "\n")
        text = TAG.replace(text, "")
        text = ENTITY.replace(text) { decodeEntity(it.groupValues[1]) }
        text = HORIZONTAL_WS.replace(text, " ")
        text = SPACE_AROUND_NEWLINE.replace(text, "\n")
        text = MANY_NEWLINES.replace(text, "\n\n")
        return text.trim()
    }

    private fun decodeEntity(name: String): String {
        if (name.startsWith("#")) {
            val code = runCatching {
                if (name.startsWith("#x", ignoreCase = true)) {
                    name.substring(2).toInt(16)
                } else {
                    name.substring(1).toInt()
                }
            }.getOrNull() ?: return "&$name;"
            return runCatching { String(Character.toChars(code)) }.getOrDefault("&$name;")
        }
        return NAMED_ENTITIES[name.lowercase()] ?: "&$name;"
    }
}

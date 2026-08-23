package com.kxin.classtable.domain.model

import com.kxin.classtable.domain.Schedule

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** AI 供应商:Gemini 或任意 OpenAI 兼容接口(DeepSeek/通义千问/Kimi/智谱 GLM 等)。 */
enum class AiProvider(val label: String, val defaultModel: String, val defaultBaseUrl: String) {
    GEMINI("Gemini", "gemini-3.6-flash", "https://generativelanguage.googleapis.com/v1beta"),
    OPENAI_COMPAT("OpenAI 兼容", "gpt-5.6-sol", "https://api.openai.com/v1"),
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentHex: String = "#C56473",
    val currentWeek: Int = 1,
    val periodTimes: String = Schedule.DEFAULT_PERIODS,
    val semesterStartDay: Long = 0L,
    val semesterWeekCount: Int = 20,
    val aiProvider: String = AiProvider.GEMINI.name,
    val aiApiKey: String = "",
    val aiBaseUrl: String = "",
    val aiModel: String = "",
    /** 课程开始前通知。 */
    val notificationsEnabled: Boolean = true,
    /** 提前多少分钟发通知(0 = 准点)。 */
    val notifyLeadMinutes: Int = 10,
) {
    fun provider(): AiProvider = runCatching { AiProvider.valueOf(aiProvider) }
        .getOrDefault(AiProvider.GEMINI)
}

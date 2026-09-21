package com.kxin.classtable.domain.model

import com.kxin.classtable.domain.Schedule

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 课程提醒形态:标准单次提醒 / 实时活动(课前到下课常驻倒计时)。 */
enum class NotifyMode { STANDARD, LIVE }

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
    /** 提醒形态:标准 / 实时活动。 */
    val notifyMode: String = NotifyMode.LIVE.name,
    /** 明日课程预告(前一天晚上提醒明天第一节与门数)。 */
    val tomorrowReminderEnabled: Boolean = true,
    /** 明日课程预告的提醒时刻("HH:MM")。 */
    val tomorrowReminderTime: String = "21:30",
    /** 是否在后台自动检查更新(每天一次,有新版发通知)。 */
    val autoCheckUpdate: Boolean = true,
    /** 上次自动检查更新的时间戳(0 = 从未检查)。 */
    val lastUpdateCheckAt: Long = 0L,
    /** 已忽略的版本号:该版本不再主动提示,手动检查仍会显示。 */
    val dismissedVersion: String = "",
    /** 首次启动权限引导是否已完成(完成/跳过后再也不弹,可随时在设置页重进)。 */
    val onboardingDone: Boolean = false,
    /**
     * 调休课表(JSON 数组):每条 = 某天停课,或某天补上另一天的课。
     * 存 JSON 而不是结构化字段,是因为它是「若干条安排」的列表,与作息同属运行时数据。
     */
    val scheduleAdjustments: String = "",
) {
    fun provider(): AiProvider = runCatching { AiProvider.valueOf(aiProvider) }
        .getOrDefault(AiProvider.GEMINI)
}

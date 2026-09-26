package com.kxin.classtable.domain.model

import com.kxin.classtable.domain.Schedule

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 课程提醒形态:标准单次提醒 / 实时活动(课前到下课常驻倒计时)。 */
enum class NotifyMode { STANDARD, LIVE }

/** 图标轮播节奏:每次打开应用 / 每小时 / 每天。 */
enum class IconCadence(val label: String) {
    LAUNCH("每次打开"),
    HOURLY("每小时"),
    DAILY("每天"),
    ;

    companion object {
        fun of(name: String): IconCadence = runCatching { valueOf(name) }.getOrDefault(LAUNCH)
    }
}

/** 后台自动检查更新的频率(手动「检查更新」不受它影响)。 */
enum class UpdateCadence(val days: Int, val label: String) {
    DAILY(1, "每天"),
    EVERY_3_DAYS(3, "每 3 天"),
    WEEKLY(7, "每周"),
    ;

    companion object {
        fun of(days: Int): UpdateCadence = entries.firstOrNull { it.days == days } ?: DAILY
    }
}

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
    /** 提醒形态:标准 / 实时活动。默认实时活动(课前到下课常驻倒计时)。 */
    val notifyMode: String = NotifyMode.LIVE.name,
    /** 明日课程预告(前一天晚上提醒明天第一节与门数)。默认开启。 */
    val tomorrowReminderEnabled: Boolean = true,
    /** 明日课程预告的提醒时刻("HH:MM")。 */
    val tomorrowReminderTime: String = "21:30",
    /** 日程到点提醒总开关(每条日程可在编辑页单独覆盖)。默认开启。 */
    val agendaReminderEnabled: Boolean = true,
    /** 全天日程在当天什么时刻提醒("HH:MM")。 */
    val agendaAllDayRemindTime: String = "09:00",
    /** 是否在后台自动检查更新(每天一次,有新版发通知)。 */
    val autoCheckUpdate: Boolean = true,
    /**
     * 是否接收预发行版(RC)。默认关闭,只推正式版;打开后「检查更新」会把 GitHub 上的
     * Pre-release 一起纳入比较 —— 想第一时间试新功能、也愿意接受偶尔不稳的人用得上。
     */
    val includePrerelease: Boolean = false,
    /** 后台自动检查更新的频率(天)。 */
    val updateCheckIntervalDays: Int = UpdateCadence.DAILY.days,
    /**
     * 上课自动免打扰:上课进免打扰、下课退出。
     *
     * 默认开启 —— 但真正生效还要用户在系统里授予「勿扰访问权限」(见 [com.kxin.classtable.notify.DndController]),
     * 没授权时它本来就不做任何事,所以不必再让用户先来开一次。
     */
    val classDndEnabled: Boolean = true,
    /** 上次自动检查更新的时间戳(0 = 从未检查)。 */
    val lastUpdateCheckAt: Long = 0L,
    /** 已忽略的版本号:该版本不再主动提示,手动检查仍会显示。 */
    val dismissedVersion: String = "",
    /** 首次启动权限引导是否已完成(完成/跳过后再也不弹,可随时在设置页重进)。 */
    val onboardingDone: Boolean = false,
    /**
     * 雨课堂公告相关开关。默认全开:登录前这些能力本来就是空转的(没有会话就没有数据),
     * 因此不必让用户再去逐个打开。
     *
     * 这些是**本机数据**(与账号无关),不进云端设置同步。
     */
    val yuketangEnabled: Boolean = true,
    /** 课前提醒里附上该课程最新一条公告。 */
    val yuketangIncludeInReminder: Boolean = true,
    /** 后台定时拉取公告。 */
    val yuketangBackgroundFetch: Boolean = true,
    /** 拉取到新公告时发通知。 */
    val yuketangNotifyNew: Boolean = true,
    /** 上次成功拉取公告的时间戳(0 = 从未);仅本机。 */
    val yuketangLastFetchAt: Long = 0L,
    /**
     * 手填的公告接口路径(「设置 → 雨课堂 → 高级」)。
     * 空 = 按内置候选自动探测;填了就只用这一条。仅本机。
     */
    val yuketangAnnouncementPath: String = "",
    /**
     * 调休课表(JSON 数组):每条 = 某天停课,或某天补上另一天的课。
     * 存 JSON 而不是结构化字段,是因为它是「若干条安排」的列表,与作息同属运行时数据。
     */
    val scheduleAdjustments: String = "",
    /**
     * 桌面图标(9 张角色图之一),取 [com.kxin.classtable.icon.AppIcon] 的序号。仅本机 ——
     * 图标是每台设备自己的事,同步到别的设备没有意义。
     */
    val appIconIndex: Int = 1,
    /**
     * 图标轮播:自动在 9 张图之间轮换。默认关闭 —— 开着会盖掉手动选的那张,
     * 想让图标固定的人不该被强制换掉。
     */
    val iconCarouselEnabled: Boolean = false,
    /** 轮播节奏(见 [IconCadence])。 */
    val iconCarouselCadence: String = IconCadence.LAUNCH.name,
) {
    fun provider(): AiProvider = runCatching { AiProvider.valueOf(aiProvider) }
        .getOrDefault(AiProvider.GEMINI)

    fun updateCadence(): UpdateCadence = UpdateCadence.of(updateCheckIntervalDays)
}

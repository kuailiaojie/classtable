package com.kxin.classtable.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.AiProvider
import com.kxin.classtable.domain.model.IconCadence
import com.kxin.classtable.domain.model.NotifyMode
import com.kxin.classtable.domain.model.ThemeMode
import com.kxin.classtable.domain.model.UpdateCadence
import com.kxin.classtable.icon.AppIcon
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 本机使用统计(驱动问卷邀请的时机判断):仅存本机,不参与设置同步。 */
data class UsageStats(
    /** 累计打开应用的次数。 */
    val launches: Int,
    /** 问卷已弹过 / 已处理:不再自动出现。 */
    val surveyHandled: Boolean,
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val KEY_THEME = stringPreferencesKey("theme_mode")
    private val KEY_ACCENT = stringPreferencesKey("accent_hex")
    private val KEY_WEEK = intPreferencesKey("current_week")
    private val KEY_PERIODS = stringPreferencesKey("period_times")
    private val KEY_SEMESTER_START = longPreferencesKey("semester_start_day")
    private val KEY_SEMESTER_WEEKS = intPreferencesKey("semester_week_count")
    private val KEY_GEMINI = stringPreferencesKey("gemini_api_key")
    private val KEY_AI_PROVIDER = stringPreferencesKey("ai_provider")
    private val KEY_AI_KEY = stringPreferencesKey("ai_api_key")
    private val KEY_AI_BASE_URL = stringPreferencesKey("ai_base_url")
    private val KEY_AI_MODEL = stringPreferencesKey("ai_model")
    private val KEY_NOTIFY_ENABLED = booleanPreferencesKey("notify_enabled")
    private val KEY_NOTIFY_LEAD = intPreferencesKey("notify_lead_minutes")
    private val KEY_NOTIFY_MODE = stringPreferencesKey("notify_mode")
    private val KEY_TOMORROW_ENABLED = booleanPreferencesKey("tomorrow_reminder_enabled")
    private val KEY_TOMORROW_TIME = stringPreferencesKey("tomorrow_reminder_time")
    /** 日程到点提醒:总开关 + 全天日程的提醒时刻。属于本机偏好,不进云端设置同步。 */
    private val KEY_AGENDA_REMIND_ENABLED = booleanPreferencesKey("agenda_reminder_enabled")
    private val KEY_AGENDA_ALLDAY_TIME = stringPreferencesKey("agenda_allday_remind_time")
    private val KEY_AUTO_CHECK_UPDATE = booleanPreferencesKey("auto_check_update")
    private val KEY_INCLUDE_PRERELEASE = booleanPreferencesKey("include_prerelease")
    private val KEY_UPDATE_INTERVAL = intPreferencesKey("update_check_interval_days")
    private val KEY_LAST_UPDATE_CHECK = longPreferencesKey("last_update_check_at")
    private val KEY_DISMISSED_VERSION = stringPreferencesKey("dismissed_version")
    private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    /** 已进过 ROM「应用启动管理」页:引导标记,仅本机;荣耀无公开 API 可查真实自启动状态,访问过即视为已配置。 */
    private val KEY_AUTO_START_VISITED = booleanPreferencesKey("autostart_visited")
    /** 本机设置最后变更时间戳:配置同步(users/{uid}/settings)LWW 判断依据;0 = 从未改过。 */
    private val KEY_SETTINGS_UPDATED_AT = longPreferencesKey("settings_updated_at")
    /** 调休课表(JSON 数组):某天停课 / 某天补另一天的课。 */
    private val KEY_ADJUSTMENTS = stringPreferencesKey("schedule_adjustments")
    private val KEY_LAUNCHES = intPreferencesKey("app_launches")
    private val KEY_SURVEY_HANDLED = booleanPreferencesKey("survey_handled")
    /** 雨课堂:开关与上次拉取时间。属于本机数据(与账号无关),不进云端设置同步。 */
    private val KEY_YKT_ENABLED = booleanPreferencesKey("yuketang_enabled")
    private val KEY_YKT_IN_REMINDER = booleanPreferencesKey("yuketang_include_in_reminder")
    private val KEY_YKT_BACKGROUND_FETCH = booleanPreferencesKey("yuketang_background_fetch")
    private val KEY_YKT_NOTIFY_NEW = booleanPreferencesKey("yuketang_notify_new")
    private val KEY_YKT_LAST_FETCH = longPreferencesKey("yuketang_last_fetch_at")
    private val KEY_YKT_ANNOUNCEMENT_PATH = stringPreferencesKey("yuketang_announcement_path")
    /** 桌面图标(9 张角色图之一)与轮播:图标是每台设备自己的事,不进云端设置同步。 */
    private val KEY_APP_ICON = intPreferencesKey("app_icon_index")
    private val KEY_ICON_CAROUSEL = booleanPreferencesKey("icon_carousel_enabled")
    private val KEY_ICON_CADENCE = stringPreferencesKey("icon_carousel_cadence")
    /** 上课自动免打扰开关。 */
    private val KEY_CLASS_DND = booleanPreferencesKey("class_dnd_enabled")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            themeMode = runCatching { ThemeMode.valueOf(p[KEY_THEME] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            accentHex = p[KEY_ACCENT] ?: "#C56473",
            currentWeek = p[KEY_WEEK] ?: 1,
            // 旧默认表(0.1.16 及更早)本身就是错的(第4节 11:00–14:00 跨午饭等),命中即视为
            // 「没设置过」换用新表;学校导入保存过的真实作息不受影响。
            periodTimes = (p[KEY_PERIODS] ?: Schedule.DEFAULT_PERIODS).let {
                if (it == Schedule.LEGACY_DEFAULT_PERIODS) Schedule.DEFAULT_PERIODS else it
            },
            semesterStartDay = p[KEY_SEMESTER_START] ?: 0L,
            semesterWeekCount = p[KEY_SEMESTER_WEEKS] ?: 20,
            aiProvider = p[KEY_AI_PROVIDER] ?: AiProvider.GEMINI.name,
            aiApiKey = p[KEY_AI_KEY] ?: p[KEY_GEMINI] ?: "",
            aiBaseUrl = p[KEY_AI_BASE_URL] ?: "",
            aiModel = p[KEY_AI_MODEL] ?: "",
            notificationsEnabled = p[KEY_NOTIFY_ENABLED] ?: true,
            notifyLeadMinutes = p[KEY_NOTIFY_LEAD] ?: 10,
            notifyMode = p[KEY_NOTIFY_MODE] ?: NotifyMode.LIVE.name,
            tomorrowReminderEnabled = p[KEY_TOMORROW_ENABLED] ?: true,
            tomorrowReminderTime = p[KEY_TOMORROW_TIME] ?: "21:30",
            agendaReminderEnabled = p[KEY_AGENDA_REMIND_ENABLED] ?: true,
            agendaAllDayRemindTime = p[KEY_AGENDA_ALLDAY_TIME] ?: "09:00",
            autoCheckUpdate = p[KEY_AUTO_CHECK_UPDATE] ?: true,
            includePrerelease = p[KEY_INCLUDE_PRERELEASE] ?: false,
            updateCheckIntervalDays = p[KEY_UPDATE_INTERVAL] ?: UpdateCadence.DAILY.days,
            lastUpdateCheckAt = p[KEY_LAST_UPDATE_CHECK] ?: 0L,
            dismissedVersion = p[KEY_DISMISSED_VERSION] ?: "",
            onboardingDone = p[KEY_ONBOARDING_DONE] ?: false,
            yuketangEnabled = p[KEY_YKT_ENABLED] ?: true,
            yuketangIncludeInReminder = p[KEY_YKT_IN_REMINDER] ?: true,
            yuketangBackgroundFetch = p[KEY_YKT_BACKGROUND_FETCH] ?: true,
            yuketangNotifyNew = p[KEY_YKT_NOTIFY_NEW] ?: true,
            yuketangLastFetchAt = p[KEY_YKT_LAST_FETCH] ?: 0L,
            yuketangAnnouncementPath = p[KEY_YKT_ANNOUNCEMENT_PATH] ?: "",
            scheduleAdjustments = p[KEY_ADJUSTMENTS] ?: "",
            appIconIndex = p[KEY_APP_ICON] ?: AppIcon.DEFAULT.ordinal,
            iconCarouselEnabled = p[KEY_ICON_CAROUSEL] ?: false,
            iconCarouselCadence = p[KEY_ICON_CADENCE] ?: IconCadence.LAUNCH.name,
            classDndEnabled = p[KEY_CLASS_DND] ?: true,
        )
    }

    /** 当前完整设置(同步推远端用)。 */
    suspend fun currentSettings(): AppSettings = settings.first()

    /** 本机设置最后变更时间戳;0 = 从未改过。 */
    suspend fun settingsUpdatedAt(): Long =
        context.settingsDataStore.data.first()[KEY_SETTINGS_UPDATED_AT] ?: 0L

    /** 应用远端配置(拉取合并后整包写回,时间戳取远端;AI 密钥与引导标记不落云端,不触碰)。 */
    suspend fun applySynced(s: AppSettings, updatedAt: Long) {
        context.settingsDataStore.edit { p ->
            p[KEY_THEME] = s.themeMode.name
            p[KEY_ACCENT] = s.accentHex
            p[KEY_WEEK] = s.currentWeek
            p[KEY_PERIODS] = s.periodTimes
            p[KEY_SEMESTER_START] = s.semesterStartDay
            p[KEY_SEMESTER_WEEKS] = s.semesterWeekCount
            p[KEY_AI_PROVIDER] = s.aiProvider
            p[KEY_AI_BASE_URL] = s.aiBaseUrl
            p[KEY_AI_MODEL] = s.aiModel
            p[KEY_NOTIFY_ENABLED] = s.notificationsEnabled
            p[KEY_NOTIFY_LEAD] = s.notifyLeadMinutes
            p[KEY_SETTINGS_UPDATED_AT] = updatedAt
        }
    }

    /** 写设置并打本地变更时间戳(仅同步字段;AI 密钥仅存本机、引导标记仅本机,单独处理)。 */
    private suspend fun editSettings(block: (MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { p ->
            block(p)
            p[KEY_SETTINGS_UPDATED_AT] = System.currentTimeMillis()
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) = editSettings { it[KEY_THEME] = mode.name }

    suspend fun setAccent(hex: String) = editSettings { it[KEY_ACCENT] = hex }

    suspend fun setCurrentWeek(week: Int) = editSettings { it[KEY_WEEK] = week }

    suspend fun setPeriodTimes(spec: String) = editSettings { it[KEY_PERIODS] = spec }

    suspend fun setSemester(startDay: Long, weekCount: Int) = editSettings {
        it[KEY_SEMESTER_START] = startDay
        it[KEY_SEMESTER_WEEKS] = weekCount
    }

    suspend fun setAiProvider(name: String) = editSettings { it[KEY_AI_PROVIDER] = name }

    /** AI 密钥仅存本机,不同步到云端;密钥变更不触发同步时间戳。 */
    suspend fun setAiKey(key: String) {
        context.settingsDataStore.edit { it[KEY_AI_KEY] = key.trim() }
    }

    suspend fun setAiBaseUrl(url: String) = editSettings { it[KEY_AI_BASE_URL] = url.trim().trimEnd('/') }

    suspend fun setAiModel(model: String) = editSettings { it[KEY_AI_MODEL] = model.trim() }

    suspend fun setNotificationsEnabled(enabled: Boolean) = editSettings { it[KEY_NOTIFY_ENABLED] = enabled }

    suspend fun setNotifyLeadMinutes(minutes: Int) = editSettings { it[KEY_NOTIFY_LEAD] = minutes.coerceIn(0, 180) }

    /** 提醒形态(标准 / 实时活动)。 */
    suspend fun setNotifyMode(mode: NotifyMode) = editSettings { it[KEY_NOTIFY_MODE] = mode.name }

    /** 明日课程预告:开关 + 时刻("HH:MM")。 */
    suspend fun setTomorrowReminder(enabled: Boolean, time: String) = editSettings {
        it[KEY_TOMORROW_ENABLED] = enabled
        it[KEY_TOMORROW_TIME] = time.trim()
    }

    /** 日程到点提醒:总开关 + 全天日程的提醒时刻("HH:MM")。每条日程的开关存在条目本身。 */
    suspend fun setAgendaReminder(enabled: Boolean, allDayTime: String) = editSettings {
        it[KEY_AGENDA_REMIND_ENABLED] = enabled
        it[KEY_AGENDA_ALLDAY_TIME] = allDayTime.trim()
    }

    suspend fun setAutoCheckUpdate(enabled: Boolean) = editSettings { it[KEY_AUTO_CHECK_UPDATE] = enabled }

    /** 是否接收预发行版(RC)更新提示。 */
    suspend fun setIncludePrerelease(enabled: Boolean) = editSettings { it[KEY_INCLUDE_PRERELEASE] = enabled }

    /** 后台自动检查更新的频率(天);手动「检查更新」不受影响。 */
    suspend fun setUpdateCheckInterval(days: Int) =
        editSettings { it[KEY_UPDATE_INTERVAL] = UpdateCadence.of(days).days }

    /** 上课自动免打扰:与提醒同属提醒类设置,走同步时间戳。 */
    suspend fun setClassDndEnabled(enabled: Boolean) = editSettings { it[KEY_CLASS_DND] = enabled }

    /** 记录一次检查(节流用);仅本机,不触发同步时间戳。 */
    suspend fun markUpdateChecked(at: Long = System.currentTimeMillis()) {
        context.settingsDataStore.edit { it[KEY_LAST_UPDATE_CHECK] = at }
    }

    /** 忽略某版本:该版本不再主动提示;仅本机。 */
    suspend fun setDismissedVersion(version: String) {
        context.settingsDataStore.edit { it[KEY_DISMISSED_VERSION] = version }
    }

    /** 首次启动权限引导完成/跳过标记:仅本机,不同步。 */
    suspend fun setOnboardingDone() {
        context.settingsDataStore.edit { it[KEY_ONBOARDING_DONE] = true }
    }

    /**
     * 调休课表(整表覆盖)。与 AI 密钥同理:属于本机课表数据,不打同步时间戳 ——
     * 否则改一次调休就会把整包设置当成「本机更新」推上云,而远端并不处理这个字段。
     */
    suspend fun setScheduleAdjustments(json: String) {
        context.settingsDataStore.edit { it[KEY_ADJUSTMENTS] = json }
    }

    /** 桌面图标(仅本机,不打同步时间戳)。 */
    suspend fun setAppIconIndex(index: Int) {
        context.settingsDataStore.edit {
            it[KEY_APP_ICON] = index.coerceIn(0, AppIcon.entries.size - 1)
        }
    }

    /** 图标轮播开关与节奏(仅本机,不打同步时间戳)。 */
    suspend fun setIconCarousel(enabled: Boolean, cadence: IconCadence) {
        context.settingsDataStore.edit { p ->
            p[KEY_ICON_CAROUSEL] = enabled
            p[KEY_ICON_CADENCE] = cadence.name
        }
    }

    /** 是否已进过 ROM「应用启动管理」页(引导标记,仅本机,不同步)。 */
    val autoStartVisited: Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_AUTO_START_VISITED] ?: false }

    /** 记录已进过 ROM「应用启动管理」页:仅本机,不触发同步时间戳。 */
    suspend fun markAutoStartVisited() {
        context.settingsDataStore.edit { it[KEY_AUTO_START_VISITED] = true }
    }

    /** 本机使用统计(打开次数 / 问卷是否已处理)。 */
    val usageStats: Flow<UsageStats> = context.settingsDataStore.data.map { p ->
        UsageStats(
            launches = p[KEY_LAUNCHES] ?: 0,
            surveyHandled = p[KEY_SURVEY_HANDLED] ?: false,
        )
    }

    /**
     * 记一次「打开了应用」。仅本机,不触发同步时间戳 —— 这是使用统计而不是用户设置,
     * 推上云没有意义,还会平白让别的设备当成「本机改过配置」。
     */
    suspend fun registerAppLaunch() {
        context.settingsDataStore.edit { p ->
            p[KEY_LAUNCHES] = (p[KEY_LAUNCHES] ?: 0) + 1
        }
    }

    /** 问卷已弹过 / 已处理:不再自动出现;仅本机。 */
    suspend fun markSurveyHandled() {
        context.settingsDataStore.edit { it[KEY_SURVEY_HANDLED] = true }
    }

    /**
     * 雨课堂开关(总开关 / 提醒内附公告 / 后台拉取 / 新公告通知)。
     *
     * 与 AI 密钥同理:属于本机能力开关,不打同步时间戳、不进 [applySynced] ——
     * 推上云没有意义,还会让别的设备白当成「本机改过配置」。
     */
    suspend fun setYuketangOptions(
        enabled: Boolean,
        includeInReminder: Boolean,
        backgroundFetch: Boolean,
        notifyNew: Boolean,
    ) {
        context.settingsDataStore.edit { p ->
            p[KEY_YKT_ENABLED] = enabled
            p[KEY_YKT_IN_REMINDER] = includeInReminder
            p[KEY_YKT_BACKGROUND_FETCH] = backgroundFetch
            p[KEY_YKT_NOTIFY_NEW] = notifyNew
        }
    }

    /** 记录一次成功的公告拉取(仅本机,不打同步时间戳)。 */
    suspend fun markYuketangFetched(at: Long = System.currentTimeMillis()) {
        context.settingsDataStore.edit { it[KEY_YKT_LAST_FETCH] = at }
    }

    /**
     * 手填的公告接口路径(仅本机):空 = 自动探测内置候选。这是排查用的逃生口 ——
     * 雨课堂的公告接口不在参考文档里,抓到真实路径后可以立刻填进来验证,不必等发版。
     */
    suspend fun setYuketangAnnouncementPath(path: String) {
        context.settingsDataStore.edit { it[KEY_YKT_ANNOUNCEMENT_PATH] = path.trim() }
    }
}

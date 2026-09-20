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
import com.kxin.classtable.domain.model.NotifyMode
import com.kxin.classtable.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

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
    private val KEY_AUTO_CHECK_UPDATE = booleanPreferencesKey("auto_check_update")
    private val KEY_LAST_UPDATE_CHECK = longPreferencesKey("last_update_check_at")
    private val KEY_DISMISSED_VERSION = stringPreferencesKey("dismissed_version")
    private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    /** 已进过 ROM「应用启动管理」页:引导标记,仅本机;荣耀无公开 API 可查真实自启动状态,访问过即视为已配置。 */
    private val KEY_AUTO_START_VISITED = booleanPreferencesKey("autostart_visited")
    /** 本机设置最后变更时间戳:配置同步(users/{uid}/settings)LWW 判断依据;0 = 从未改过。 */
    private val KEY_SETTINGS_UPDATED_AT = longPreferencesKey("settings_updated_at")

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
            notifyMode = p[KEY_NOTIFY_MODE] ?: NotifyMode.STANDARD.name,
            tomorrowReminderEnabled = p[KEY_TOMORROW_ENABLED] ?: false,
            tomorrowReminderTime = p[KEY_TOMORROW_TIME] ?: "21:30",
            autoCheckUpdate = p[KEY_AUTO_CHECK_UPDATE] ?: true,
            lastUpdateCheckAt = p[KEY_LAST_UPDATE_CHECK] ?: 0L,
            dismissedVersion = p[KEY_DISMISSED_VERSION] ?: "",
            onboardingDone = p[KEY_ONBOARDING_DONE] ?: false,
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

    suspend fun setAutoCheckUpdate(enabled: Boolean) = editSettings { it[KEY_AUTO_CHECK_UPDATE] = enabled }

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

    /** 是否已进过 ROM「应用启动管理」页(引导标记,仅本机,不同步)。 */
    val autoStartVisited: Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_AUTO_START_VISITED] ?: false }

    /** 记录已进过 ROM「应用启动管理」页:仅本机,不触发同步时间戳。 */
    suspend fun markAutoStartVisited() {
        context.settingsDataStore.edit { it[KEY_AUTO_START_VISITED] = true }
    }
}

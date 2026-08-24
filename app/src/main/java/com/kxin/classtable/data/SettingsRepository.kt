package com.kxin.classtable.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.AiProvider
import com.kxin.classtable.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
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
    private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            themeMode = runCatching { ThemeMode.valueOf(p[KEY_THEME] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            accentHex = p[KEY_ACCENT] ?: "#C56473",
            currentWeek = p[KEY_WEEK] ?: 1,
            periodTimes = p[KEY_PERIODS] ?: Schedule.DEFAULT_PERIODS,
            semesterStartDay = p[KEY_SEMESTER_START] ?: 0L,
            semesterWeekCount = p[KEY_SEMESTER_WEEKS] ?: 20,
            aiProvider = p[KEY_AI_PROVIDER] ?: AiProvider.GEMINI.name,
            aiApiKey = p[KEY_AI_KEY] ?: p[KEY_GEMINI] ?: "",
            aiBaseUrl = p[KEY_AI_BASE_URL] ?: "",
            aiModel = p[KEY_AI_MODEL] ?: "",
            notificationsEnabled = p[KEY_NOTIFY_ENABLED] ?: true,
            notifyLeadMinutes = p[KEY_NOTIFY_LEAD] ?: 10,
            onboardingDone = p[KEY_ONBOARDING_DONE] ?: false,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[KEY_THEME] = mode.name }
    }

    suspend fun setAccent(hex: String) {
        context.settingsDataStore.edit { it[KEY_ACCENT] = hex }
    }

    suspend fun setCurrentWeek(week: Int) {
        context.settingsDataStore.edit { it[KEY_WEEK] = week }
    }

    suspend fun setPeriodTimes(spec: String) {
        context.settingsDataStore.edit { it[KEY_PERIODS] = spec }
    }

    suspend fun setSemester(startDay: Long, weekCount: Int) {
        context.settingsDataStore.edit {
            it[KEY_SEMESTER_START] = startDay
            it[KEY_SEMESTER_WEEKS] = weekCount
        }
    }

    suspend fun setAiProvider(name: String) {
        context.settingsDataStore.edit { it[KEY_AI_PROVIDER] = name }
    }

    suspend fun setAiKey(key: String) {
        context.settingsDataStore.edit { it[KEY_AI_KEY] = key.trim() }
    }

    suspend fun setAiBaseUrl(url: String) {
        context.settingsDataStore.edit { it[KEY_AI_BASE_URL] = url.trim().trimEnd('/') }
    }

    suspend fun setAiModel(model: String) {
        context.settingsDataStore.edit { it[KEY_AI_MODEL] = model.trim() }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_NOTIFY_ENABLED] = enabled }
    }

    suspend fun setNotifyLeadMinutes(minutes: Int) {
        context.settingsDataStore.edit { it[KEY_NOTIFY_LEAD] = minutes.coerceIn(0, 180) }
    }

    suspend fun setOnboardingDone() {
        context.settingsDataStore.edit { it[KEY_ONBOARDING_DONE] = true }
    }
}

package com.kxin.classtable.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.updateAll
import com.kxin.classtable.data.settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/** 小组件底色风格。 */
enum class WidgetSurface {
    /** 纸面:始终浅色,和 App 的纸面一致。 */
    PAPER,

    /** 深色。 */
    INK,

    /** 透明:交给壁纸,只压一层极淡的罩,文字随系统深浅。 */
    CLEAR,
}

/**
 * 小组件的内容与外观选项。
 *
 * [accentHex] 不在这个页面上单独设 —— 它跟随「设置 → 外观」的强调色,避免同一个 App 里
 * 出现两套「主色」。这里只是把它一起读出来,省一次 DataStore 读取。
 */
data class WidgetPrefs(
    val showTime: Boolean = true,
    val showLocation: Boolean = true,
    val showTeacher: Boolean = false,
    val showPeriod: Boolean = false,
    val compact: Boolean = false,
    val surface: WidgetSurface = WidgetSurface.PAPER,
    /** 今日课表最多显示几门。 */
    val maxRows: Int = 4,
    val accentHex: String = "#C56473",
)

object WidgetPrefsStore {
    private val KEY_TIME = booleanPreferencesKey("widget_show_time")
    private val KEY_LOCATION = booleanPreferencesKey("widget_show_location")
    private val KEY_TEACHER = booleanPreferencesKey("widget_show_teacher")
    private val KEY_PERIOD = booleanPreferencesKey("widget_show_period")
    private val KEY_COMPACT = booleanPreferencesKey("widget_compact")
    private val KEY_SURFACE = stringPreferencesKey("widget_surface")
    private val KEY_MAX_ROWS = intPreferencesKey("widget_max_rows")
    private val KEY_ACCENT = stringPreferencesKey("accent_hex")

    /** 谁都不在时,小组件只显示「课程名 + 时刻」,和以前一致。 */
    fun flow(context: Context): Flow<WidgetPrefs> = context.settingsDataStore.data.map { p ->
        WidgetPrefs(
            showTime = p[KEY_TIME] ?: true,
            showLocation = p[KEY_LOCATION] ?: true,
            showTeacher = p[KEY_TEACHER] ?: false,
            showPeriod = p[KEY_PERIOD] ?: false,
            compact = p[KEY_COMPACT] ?: false,
            surface = runCatching { WidgetSurface.valueOf(p[KEY_SURFACE] ?: "PAPER") }
                .getOrDefault(WidgetSurface.PAPER),
            maxRows = (p[KEY_MAX_ROWS] ?: 4).coerceIn(1, 8),
            accentHex = p[KEY_ACCENT] ?: "#C56473",
        )
    }

    fun read(context: Context): WidgetPrefs = runBlocking { flow(context).first() }

    suspend fun save(context: Context, prefs: WidgetPrefs) {
        context.settingsDataStore.edit { p ->
            p[KEY_TIME] = prefs.showTime
            p[KEY_LOCATION] = prefs.showLocation
            p[KEY_TEACHER] = prefs.showTeacher
            p[KEY_PERIOD] = prefs.showPeriod
            p[KEY_COMPACT] = prefs.compact
            p[KEY_SURFACE] = prefs.surface.name
            p[KEY_MAX_ROWS] = prefs.maxRows
        }
    }

    /** 选项改完刷新全部小组件(不刷新就得等系统下一次 updatePeriod)。 */
    suspend fun refreshWidgets(context: Context) {
        runCatching { NextClassWidget().updateAll(context) }
        runCatching { TodayWidget().updateAll(context) }
        runCatching { TomorrowWidget().updateAll(context) }
        runCatching { AgendaWidget().updateAll(context) }
    }
}

/** 小组件的配色。 */
data class WidgetSkin(
    val background: Color,
    val ink: Color,
    val sub: Color,
    val accent: Color,
)

fun skinOf(context: Context, prefs: WidgetPrefs): WidgetSkin {
    val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val accent = runCatching { Color(android.graphics.Color.parseColor(prefs.accentHex)) }
        .getOrDefault(Color(0xFFC56473))
    return when (prefs.surface) {
        WidgetSurface.PAPER -> WidgetSkin(
            background = Color(0xFFFEFEFB),
            ink = Color(0xFF141312),
            sub = Color(0xFF5C5A55),
            accent = accent,
        )
        WidgetSurface.INK -> WidgetSkin(
            background = Color(0xFF141414),
            ink = Color(0xFFF8F8F8),
            sub = Color(0xFFA8A8A8),
            accent = accent,
        )
        WidgetSurface.CLEAR -> WidgetSkin(
            // 轻透:不做实底,只压一层很淡的暗/亮罩 —— 全透明在花哨壁纸上会读不出字,
            // 而且 Glance 没有 backdrop 模糊可用。
            background = if (systemDark) Color(0x33FFFFFF) else Color(0x1F000000),
            ink = if (systemDark) Color(0xFFF8F8F8) else Color(0xFF141312),
            sub = if (systemDark) Color(0xFFA8A8A8) else Color(0xFF5C5A55),
            accent = accent,
        )
    }
}

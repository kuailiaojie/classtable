package com.kxin.classtable.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 周视图行高密度:一屏放下多少节次。 */
enum class GridDensity { LOOSE, STANDARD, COMPACT }

/** 课程名字号档位。 */
enum class NameSize { SMALL, MEDIUM, LARGE }

/** 课程块圆角档位。 */
enum class BlockCorner { SQUARE, MEDIUM, ROUND }

/**
 * 课表页(周视图课程块)的显示偏好。
 *
 * 与「桌面小组件」同一套路:单独一份、存本机 DataStore、不参与云同步 ——
 * 它是「这块屏怎么画」的个人偏好,换台设备各随各的就好。
 */
data class TimetablePrefs(
    /** 课程块里显示「08:00」这样的具体时刻。 */
    val showTime: Boolean = false,
    /** 课程块里显示「1-2节」。 */
    val showPeriod: Boolean = false,
    val showLocation: Boolean = true,
    val showTeacher: Boolean = false,
    val density: GridDensity = GridDensity.STANDARD,
    /** 课程名显示行数:1 或 2。 */
    val nameLines: Int = 2,
    val nameSize: NameSize = NameSize.MEDIUM,
    val corner: BlockCorner = BlockCorner.MEDIUM,
    /** 是否铺课程淡彩底;关闭 = 纯文字块(浮起面 + 细边框)。 */
    val showTint: Boolean = true,
)

object TimetablePrefsStore {
    private val KEY_TIME = booleanPreferencesKey("timetable_show_time")
    private val KEY_PERIOD = booleanPreferencesKey("timetable_show_period")
    private val KEY_LOCATION = booleanPreferencesKey("timetable_show_location")
    private val KEY_TEACHER = booleanPreferencesKey("timetable_show_teacher")
    private val KEY_DENSITY = stringPreferencesKey("timetable_density")
    private val KEY_NAME_LINES = intPreferencesKey("timetable_name_lines")
    private val KEY_NAME_SIZE = stringPreferencesKey("timetable_name_size")
    private val KEY_CORNER = stringPreferencesKey("timetable_corner")
    private val KEY_TINT = booleanPreferencesKey("timetable_show_tint")

    fun flow(context: Context): Flow<TimetablePrefs> = context.settingsDataStore.data.map { p ->
        TimetablePrefs(
            showTime = p[KEY_TIME] ?: false,
            showPeriod = p[KEY_PERIOD] ?: false,
            showLocation = p[KEY_LOCATION] ?: true,
            showTeacher = p[KEY_TEACHER] ?: false,
            density = runCatching { GridDensity.valueOf(p[KEY_DENSITY] ?: "STANDARD") }
                .getOrDefault(GridDensity.STANDARD),
            nameLines = (p[KEY_NAME_LINES] ?: 2).coerceIn(1, 2),
            nameSize = runCatching { NameSize.valueOf(p[KEY_NAME_SIZE] ?: "MEDIUM") }
                .getOrDefault(NameSize.MEDIUM),
            corner = runCatching { BlockCorner.valueOf(p[KEY_CORNER] ?: "MEDIUM") }
                .getOrDefault(BlockCorner.MEDIUM),
            showTint = p[KEY_TINT] ?: true,
        )
    }

    suspend fun save(context: Context, prefs: TimetablePrefs) {
        context.settingsDataStore.edit { p ->
            p[KEY_TIME] = prefs.showTime
            p[KEY_PERIOD] = prefs.showPeriod
            p[KEY_LOCATION] = prefs.showLocation
            p[KEY_TEACHER] = prefs.showTeacher
            p[KEY_DENSITY] = prefs.density.name
            p[KEY_NAME_LINES] = prefs.nameLines
            p[KEY_NAME_SIZE] = prefs.nameSize.name
            p[KEY_CORNER] = prefs.corner.name
            p[KEY_TINT] = prefs.showTint
        }
    }
}

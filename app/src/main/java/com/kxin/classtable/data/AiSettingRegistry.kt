package com.kxin.classtable.data

import com.kxin.classtable.design.AccentOptions
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.enumOrDefault
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.NotifyMode
import com.kxin.classtable.domain.model.ThemeMode
import java.time.LocalDate
import java.util.Locale

/**
 * 可被助手修改的设置项**白名单**。
 *
 * 助手改设置与改课表同一套纪律:先用 [describe] 把它能改的键与取值范围告诉模型,本地按 [normalize]
 * 校验,确认后经 [apply] 走**与设置页完全相同的 setter** —— 于是「设置页能拒绝的,助手也拒绝」。
 * 白名单之外的能力(壁纸 / 备份 / 教务登录 / 系统权限)不在其中,只能由用户在原页面完成。
 */
object AiSettingRegistry {

    enum class Kind { BOOL, CHOICE, INT, TIME, DATE }

    data class Entry(
        val key: String,
        val label: String,
        val kind: Kind,
        val choices: List<String> = emptyList(),
        val aliases: Map<String, String> = emptyMap(),
        val min: Int = 0,
        val max: Int = 0,
        val current: (AppSettings) -> String,
        val apply: suspend (SettingsRepository, String) -> Unit,
    )

    private val accentAliases: Map<String, String> = AccentOptions.associate { it.first to it.second }

    val entries: List<Entry> = listOf(
        Entry(
            key = "THEME", label = "主题", kind = Kind.CHOICE,
            choices = listOf("SYSTEM", "LIGHT", "DARK"),
            aliases = mapOf("跟随系统" to "SYSTEM", "浅色" to "LIGHT", "深色" to "DARK"),
            current = { it.themeMode.name },
            apply = { r, v -> r.setThemeMode(enumOrDefault(v, ThemeMode.SYSTEM)) },
        ),
        Entry(
            key = "ACCENT", label = "强调色", kind = Kind.CHOICE,
            choices = AccentOptions.map { it.second },
            aliases = accentAliases,
            current = { it.accentHex },
            apply = { r, v -> r.setAccent(v) },
        ),
        Entry(
            key = "NOTIFY_ENABLED", label = "课程提醒开关", kind = Kind.BOOL,
            current = { it.notificationsEnabled.toString() },
            apply = { r, v -> r.setNotificationsEnabled(parseBool(v)) },
        ),
        Entry(
            key = "NOTIFY_LEAD", label = "课前提前分钟", kind = Kind.INT, min = 0, max = 180,
            current = { it.notifyLeadMinutes.toString() },
            apply = { r, v -> r.setNotifyLeadMinutes(v.toInt()) },
        ),
        Entry(
            key = "NOTIFY_MODE", label = "提醒形态", kind = Kind.CHOICE,
            choices = listOf("LIVE", "STANDARD"),
            aliases = mapOf("实时活动" to "LIVE", "胶囊" to "LIVE", "标准" to "STANDARD"),
            current = { it.notifyMode },
            apply = { r, v -> r.setNotifyMode(enumOrDefault(v, NotifyMode.STANDARD)) },
        ),
        Entry(
            key = "TOMORROW_ENABLED", label = "明日课程预告开关", kind = Kind.BOOL,
            current = { it.tomorrowReminderEnabled.toString() },
            apply = { r, v -> r.setTomorrowReminder(parseBool(v), r.currentSettings().tomorrowReminderTime) },
        ),
        Entry(
            key = "TOMORROW_TIME", label = "明日预告时刻", kind = Kind.TIME,
            current = { it.tomorrowReminderTime },
            apply = { r, v -> r.setTomorrowReminder(r.currentSettings().tomorrowReminderEnabled, v) },
        ),
        Entry(
            key = "AGENDA_REMIND_ENABLED", label = "日程提醒开关", kind = Kind.BOOL,
            current = { it.agendaReminderEnabled.toString() },
            apply = { r, v -> r.setAgendaReminder(parseBool(v), r.currentSettings().agendaAllDayRemindTime) },
        ),
        Entry(
            key = "AGENDA_ALLDAY_TIME", label = "全天日程提醒时刻", kind = Kind.TIME,
            current = { it.agendaAllDayRemindTime },
            apply = { r, v -> r.setAgendaReminder(r.currentSettings().agendaReminderEnabled, v) },
        ),
        Entry(
            key = "CLASS_DND", label = "上课免打扰", kind = Kind.BOOL,
            current = { it.classDndEnabled.toString() },
            apply = { r, v -> r.setClassDndEnabled(parseBool(v)) },
        ),
        Entry(
            key = "SEMESTER_WEEKS", label = "学期总周数", kind = Kind.INT, min = 1, max = 30,
            current = { it.semesterWeekCount.toString() },
            apply = { r, v -> r.setSemester(r.currentSettings().semesterStartDay, v.toInt()) },
        ),
    )

    fun find(key: String): Entry? = entries.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) }

    /** 给模型看的键目录(不含当前值):`键: 允许取值 —— 说明`。 */
    fun describe(): String = entries.joinToString("\n") { e ->
        val allowed = when (e.kind) {
            Kind.BOOL -> "true|false"
            Kind.CHOICE -> e.choices.joinToString("|")
            Kind.INT -> "${e.min}..${e.max}"
            Kind.TIME -> "HH:MM"
            Kind.DATE -> "yyyy-MM-dd"
        }
        "${e.key}: $allowed —— ${e.label}"
    }

    /** 给工具输出用:带上当前值,便于模型判断「要不要改」。 */
    fun describeWithCurrent(settings: AppSettings): String = entries.joinToString("\n") { e ->
        val allowed = when (e.kind) {
            Kind.BOOL -> "true|false"
            Kind.CHOICE -> e.choices.joinToString("|")
            Kind.INT -> "${e.min}..${e.max}"
            Kind.TIME -> "HH:MM"
            Kind.DATE -> "yyyy-MM-dd"
        }
        "${e.key}: $allowed —— ${e.label},当前 ${e.current(settings)}"
    }

    /** 归一化:合法则给出规范值(如 "深色" → "DARK"),否则 null。 */
    fun normalize(entry: Entry, raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        val lower = v.lowercase(Locale.ROOT)
        return when (entry.kind) {
            Kind.BOOL -> when (lower) {
                "true", "1", "on", "yes", "开", "开启" -> "true"
                "false", "0", "off", "no", "关", "关闭" -> "false"
                else -> null
            }

            Kind.CHOICE -> entry.choices.firstOrNull { it.equals(v, true) }
                ?: entry.aliases.entries.firstOrNull { it.key.equals(v, true) }?.value

            Kind.INT -> v.toIntOrNull()?.takeIf { it in entry.min..entry.max }?.toString()

            Kind.TIME -> Schedule.parseClock(v)?.let { "%02d:%02d".format(it / 60, it % 60) }

            Kind.DATE -> v.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
        }
    }

    /** 校验:返回人类可读的错误,或 null 表示通过。 */
    fun validate(entry: Entry, raw: String): String? {
        if (normalize(entry, raw) != null) return null
        val allowed = when (entry.kind) {
            Kind.BOOL -> "true / false"
            Kind.CHOICE -> entry.choices.joinToString(" / ")
            Kind.INT -> "${entry.min}~${entry.max} 的整数"
            Kind.TIME -> "24 小时 HH:MM"
            Kind.DATE -> "yyyy-MM-dd"
        }
        return "设置 ${entry.key}(${entry.label})只接受 $allowed,收到的是「$raw」"
    }

    private fun parseBool(v: String): Boolean = v.equals("true", true)
}

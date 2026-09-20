package com.kxin.classtable.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.local.CourseDao
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.NotifyMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 课程提醒排程。
 *
 * 与旧实现的关键差别(照 [SleepDown-Schedule](https://github.com/xiaomanjun233/SleepDown-Schedule) 的提醒架构):
 * - **排程签名**:课程/作息/学期/通知设置的指纹,只有指纹变化(或强制)时才真正重排 —— 不再
 *   「课程或设置每次发射都全量取消+重排」。
 * - **已排台账**:把真正排出去的 `requestCode|action|key` 记在本地,取消时精确撤销,不再盲扫 120 天。
 * - **滚动窗口 + 自续期**:窗口 8 天,并额外排一个次日 00:05 的「刷新」闹钟,由接收器强制重排 ——
 *   App 长期不开也能把窗口往前滚。
 * - **实时活动重试补发**:实时活动的触发点会排 +0/+1/+3/+5 分钟四次(提前量不同导致的
 *   系统丢闹钟/延迟送达,后一次仍能把状态补上)。
 * - **静音**:「取消本节课提醒」按「课程:日期」记到下课为止,重试与重启后依然生效。
 */
@Singleton
class ReminderPlanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: CourseDao,
    private val settingsRepository: SettingsRepository,
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /**
     * 重排全部提醒。幂等:签名没变且非强制时直接返回。
     * 保持非 suspend(旧调用方遍布 App/仓库/接收器),内部 runBlocking 读一次配置与课程。
     */
    fun rescheduleAll(force: Boolean = false) {
        runBlocking {
            val settings = settingsRepository.settings.first()
            val courses = dao.getAll().map { it.toDomain() }
            val today = LocalDate.now()
            val signature = signature(courses, settings, today)
            val unchanged = !force && prefs.getString(KEY_SIGNATURE, null) == signature
            if (unchanged) {
                return@runBlocking
            }
            scheduleWindow(courses, settings, today)
            prefs.edit().putString(KEY_SIGNATURE, signature).apply()
        }
    }

    /** 取消某课程已排的全部提醒(课程被删除时调用)。 */
    fun cancelCourse(courseId: String) {
        val kept = mutableListOf<String>()
        ledger().forEach { entry ->
            if (entry.key == courseId) {
                cancelEntry(entry)
            } else {
                kept += entry.encode()
            }
        }
        prefs.edit().putString(KEY_LEDGER, kept.joinToString("\n")).apply()
    }

    /** 静音到指定时刻(「取消本节课提醒」)。 */
    fun mute(key: String, untilMillis: Long) {
        prefs.edit().putString(KEY_MUTED_KEY, key).putLong(KEY_MUTED_UNTIL, untilMillis).apply()
    }

    /** 该「课程:日期」是否已被静音(到点自动失效)。 */
    fun isMuted(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val mutedKey = prefs.getString(KEY_MUTED_KEY, null) ?: return false
        val until = prefs.getLong(KEY_MUTED_UNTIL, 0L)
        if (until <= now) {
            prefs.edit().remove(KEY_MUTED_KEY).remove(KEY_MUTED_UNTIL).apply()
            return false
        }
        return mutedKey == key
    }

    // —— 实时活动的 payload 持久化:进程被杀后服务重启仍能恢复「正在上的那节课」 ——

    fun saveLive(payload: LiveCourse) {
        prefs.edit().putString(KEY_LIVE, payload.toJson().toString()).apply()
    }

    fun restoreLive(): LiveCourse? = prefs.getString(KEY_LIVE, null)?.let { raw ->
        runCatching { LiveCourse.fromJson(JSONObject(raw)) }.getOrNull()
    }

    fun clearLive() {
        prefs.edit().remove(KEY_LIVE).apply()
    }

    // —— 内部:排程 ——

    private fun scheduleWindow(courses: List<Course>, settings: AppSettings, today: LocalDate) {
        cancelLedger()
        if (!settings.notificationsEnabled) {
            saveLedger(emptyList())
            return
        }
        val now = System.currentTimeMillis()
        val lead = settings.notifyLeadMinutes.coerceIn(0, 180)
        val periods = Schedule.parsePeriods(settings.periodTimes)
        val zone = ZoneId.systemDefault()
        val live = settings.notifyMode == NotifyMode.LIVE.name
        val entries = mutableListOf<Entry>()

        for (offset in 0 until HORIZON_DAYS) {
            val date = today.plusDays(offset.toLong())
            val week = Schedule.weekOf(date.toEpochDay(), settings.semesterStartDay, settings.semesterWeekCount)
            val dayCourses = courses.filter { it.isOnWeekday(date.dayOfWeek.value) && it.isActiveOnWeek(week) }

            dayCourses.forEach { course ->
                val startMinute = Schedule.courseStartMinute(course, periods) ?: return@forEach
                val endMinute = Schedule.courseEndMinute(course, periods)
                    ?: (startMinute + Schedule.PERIOD_LENGTH_MIN)
                val startAt = millisAt(date, startMinute, zone)
                val endAt = millisAt(date, endMinute, zone)
                if (endAt <= now) return@forEach
                val key = "${course.id}:${date.toEpochDay()}"
                val firstTrigger = startAt - lead * 60_000L
                // 实时活动:多发几次,系统丢闹钟/延迟也能补上;普通提醒只需一次。
                val triggers = if (live) {
                    listOf(0L, 60_000L, 3 * 60_000L, 5 * 60_000L).map { firstTrigger + it }
                } else {
                    listOf(firstTrigger)
                }
                triggers.forEachIndexed { index, trigger ->
                    if (trigger <= now || trigger >= endAt) return@forEachIndexed
                    val payload = LiveCourse(
                        courseId = course.id,
                        name = course.name,
                        location = course.location,
                        startAtMillis = startAt,
                        endAtMillis = endAt,
                        leadMinutes = lead,
                        muteKey = key,
                    )
                    val code = eventCode(date.toEpochDay(), course.id, index)
                    scheduleAlarm(
                        trigger = trigger,
                        requestCode = code,
                        intent = reminderIntent(payload, live, startMinute),
                    )
                    entries += Entry(code, ACTION_REMIND, course.id)
                }
            }

            // 明日课程预告(可选):前一天指定时刻提醒
            if (settings.tomorrowReminderEnabled && offset in 0 until HORIZON_DAYS - 1) {
                val nextDate = date.plusDays(1)
                val nextWeek = Schedule.weekOf(
                    nextDate.toEpochDay(), settings.semesterStartDay, settings.semesterWeekCount,
                )
                val nextCourses = courses.filter {
                    it.isOnWeekday(nextDate.dayOfWeek.value) && it.isActiveOnWeek(nextWeek)
                }
                if (nextCourses.isEmpty()) continue
                val minute = Schedule.parseClock(settings.tomorrowReminderTime) ?: DEFAULT_TOMORROW_MINUTE
                val trigger = millisAt(date, minute, zone)
                if (trigger <= now) continue
                val first = nextCourses.minByOrNull {
                    Schedule.courseStartMinute(it, periods) ?: Int.MAX_VALUE
                }
                val firstStart = first?.let { Schedule.courseStartMinute(it, periods) }
                val code = eventCode(date.toEpochDay(), TOMORROW_KEY, 0)
                val intent = Intent(context, CourseReminderReceiver::class.java)
                    .setAction(ACTION_REMIND)
                    .putExtra(EXTRA_TOMORROW, true)
                    .putExtra(EXTRA_TOMORROW_COUNT, nextCourses.size)
                    .putExtra(EXTRA_COURSE_NAME, first?.name.orEmpty())
                    .putExtra(EXTRA_LOCATION, first?.location.orEmpty())
                    .putExtra(EXTRA_START_MINUTE, firstStart ?: -1)
                    .putExtra(EXTRA_MUTE_KEY, "tomorrow:${nextDate.toEpochDay()}")
                scheduleAlarm(trigger, code, intent)
                entries += Entry(code, ACTION_REMIND, TOMORROW_KEY)
            }
        }

        // 自续期:次日 00:05 强制重排,把窗口整体往前滚(不依赖 App 被打开)
        val maintenanceAt = today.plusDays(1).atTime(0, 5)
            .atZone(zone).toInstant().toEpochMilli()
        val maintenanceCode = eventCode(today.plusDays(1).toEpochDay(), REFRESH_KEY, 99)
        scheduleAlarm(
            trigger = maintenanceAt,
            requestCode = maintenanceCode,
            intent = Intent(context, CourseReminderReceiver::class.java).setAction(ACTION_REFRESH),
        )
        entries += Entry(maintenanceCode, ACTION_REFRESH, REFRESH_KEY)

        saveLedger(entries)
        Log.i(TAG, "已排 ${entries.size} 个提醒闹钟(窗口 $HORIZON_DAYS 天,模式=${settings.notifyMode})")
    }

    private fun reminderIntent(payload: LiveCourse, live: Boolean, startMinute: Int): Intent =
        Intent(context, CourseReminderReceiver::class.java)
            .setAction(ACTION_REMIND)
            .putExtra(EXTRA_LIVE, live)
            .putExtra(EXTRA_COURSE_ID, payload.courseId)
            .putExtra(EXTRA_COURSE_NAME, payload.name)
            .putExtra(EXTRA_LOCATION, payload.location)
            .putExtra(EXTRA_START_MILLIS, payload.startAtMillis)
            .putExtra(EXTRA_END_MILLIS, payload.endAtMillis)
            .putExtra(EXTRA_LEAD, payload.leadMinutes)
            .putExtra(EXTRA_MUTE_KEY, payload.muteKey)
            .putExtra(EXTRA_START_MINUTE, startMinute)

    private fun scheduleAlarm(trigger: Long, requestCode: Int, intent: Intent) {
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
                // 未授予精确闹钟:退到窗口闹钟(±1 分钟),并在权限恢复后由 Boot 接收器重排
                alarmManager.setWindow(AlarmManager.RTC_WAKEUP, trigger, 60_000L, pi)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "精确闹钟被拒绝,退回窗口闹钟", error)
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, trigger, 60_000L, pi)
        }
    }

    // —— 内部:台账 ——

    private data class Entry(val code: Int, val action: String, val key: String) {
        fun encode(): String = "$code|$action|$key"
    }

    private fun ledger(): List<Entry> = prefs.getString(KEY_LEDGER, null)
        .orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val parts = line.split('|')
            val code = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val action = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: ACTION_REMIND
            Entry(code, action, parts.getOrNull(2).orEmpty())
        }
        .toList()

    private fun saveLedger(entries: List<Entry>) {
        prefs.edit().putString(KEY_LEDGER, entries.joinToString("\n") { it.encode() }).apply()
    }

    private fun cancelLedger() {
        ledger().forEach(::cancelEntry)
        prefs.edit().remove(KEY_LEDGER).apply()
    }

    /** 取消用 PendingIntent:extras 不参与匹配,同 class + action + requestCode 即命中。 */
    private fun cancelEntry(entry: Entry) {
        val intent = Intent(context, CourseReminderReceiver::class.java).setAction(entry.action)
        val pi = PendingIntent.getBroadcast(
            context,
            entry.code,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pi != null) {
            alarmManager.cancel(pi)
            pi.cancel()
        }
    }

    private fun signature(courses: List<Course>, s: AppSettings, today: LocalDate): String {
        val coursePart = courses.sortedBy { it.id }.joinToString(";") { c ->
            listOf(
                c.id, c.name, c.location, c.teacher,
                c.weekday, c.weekdays, "${c.startPeriod}-${c.endPeriod}",
                c.weekType.name, c.weekStart, c.weekEnd,
                c.customStartMinute ?: "-", c.customEndMinute ?: "-",
            ).joinToString(":")
        }
        return listOf(
            SIGNATURE_VERSION,
            today.toString(),
            s.periodTimes,
            s.semesterStartDay,
            s.semesterWeekCount,
            s.notificationsEnabled,
            s.notifyLeadMinutes,
            s.notifyMode,
            s.tomorrowReminderEnabled,
            s.tomorrowReminderTime,
            coursePart,
        ).joinToString("|")
    }

    private fun eventCode(epochDay: Long, courseKey: String, index: Int): Int =
        (listOf(epochDay, courseKey, index).hashCode()) and Int.MAX_VALUE

    private fun millisAt(date: LocalDate, minute: Int, zone: ZoneId): Long =
        date.atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()

    companion object {
        private const val TAG = "ReminderPlanner"
        private const val PREFS = "reminder_plan"
        private const val KEY_SIGNATURE = "signature"
        private const val KEY_LEDGER = "ledger"
        private const val KEY_MUTED_KEY = "muted_key"
        private const val KEY_MUTED_UNTIL = "muted_until"
        private const val KEY_LIVE = "live_payload"
        private const val SIGNATURE_VERSION = "plan-v1"

        /** 滚动窗口天数(含今天)。 */
        const val HORIZON_DAYS = 8

        const val ACTION_REMIND = "com.kxin.classtable.ACTION_COURSE_REMIND"
        const val ACTION_REFRESH = "com.kxin.classtable.ACTION_REFRESH_REMINDERS"

        const val EXTRA_LIVE = "live"
        const val EXTRA_COURSE_ID = "course_id"
        const val EXTRA_COURSE_NAME = "course_name"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_START_MILLIS = "start_millis"
        const val EXTRA_END_MILLIS = "end_millis"
        const val EXTRA_START_MINUTE = "start_minute"
        const val EXTRA_LEAD = "lead_minutes"
        const val EXTRA_MUTE_KEY = "mute_key"
        const val EXTRA_TOMORROW = "tomorrow"
        const val EXTRA_TOMORROW_COUNT = "tomorrow_count"

        const val TOMORROW_KEY = "tomorrow"
        const val REFRESH_KEY = "refresh"
        private const val DEFAULT_TOMORROW_MINUTE = 21 * 60 + 30

        /** 短时唤醒锁:接收器要在系统再次休眠前读完数据并发通知。 */
        fun withShortWakeLock(context: Context, tag: String, block: () -> Unit) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val lock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "classtable:$tag")
            runCatching { lock?.acquire(5_000L) }
            try {
                block()
            } finally {
                if (lock?.isHeld == true) runCatching { lock.release() }
            }
        }
    }
}

/** 实时活动需要展示的一节课。 */
data class LiveCourse(
    val courseId: String,
    val name: String,
    val location: String,
    val startAtMillis: Long,
    val endAtMillis: Long,
    val leadMinutes: Int,
    val muteKey: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("courseId", courseId)
        .put("name", name)
        .put("location", location)
        .put("startAt", startAtMillis)
        .put("endAt", endAtMillis)
        .put("lead", leadMinutes)
        .put("muteKey", muteKey)

    companion object {
        fun fromJson(json: JSONObject) = LiveCourse(
            courseId = json.optString("courseId"),
            name = json.optString("name"),
            location = json.optString("location"),
            startAtMillis = json.optLong("startAt"),
            endAtMillis = json.optLong("endAt"),
            leadMinutes = json.optInt("lead", 10),
            muteKey = json.optString("muteKey"),
        )
    }
}

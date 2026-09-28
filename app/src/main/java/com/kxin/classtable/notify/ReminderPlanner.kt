package com.kxin.classtable.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.local.AgendaDao
import com.kxin.classtable.data.local.CourseDao
import com.kxin.classtable.domain.Adjustments
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.ScheduleAdjustment
import com.kxin.classtable.domain.model.AgendaEvent
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.NotifyMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 课程 / 日程提醒排程。
 *
 * 与旧实现的关键差别:
 * - **排程签名**:课程/日程/作息/学期/通知设置的指纹,只有指纹变化(或强制)时才真正重排 —— 不再
 *   「课程或设置每次发射都全量取消+重排」。
 * - **已排台账**:把真正排出去的 `requestCode|action|key` 记在本地,取消时精确撤销,不再盲扫 120 天。
 * - **滚动窗口 + 自续期**:窗口 8 天,并额外排一个次日 00:05 的「刷新」闹钟,由接收器强制重排 ——
 *   App 长期不开也能把窗口往前滚。
 * - **不再排「提醒后 1/3/5 分钟」的重试**:那几次重发没有新状态,却会各自拉起一次前台服务 ——
 *   在国产胶囊眼里就是「又一条新提醒」,课前那几分钟里胶囊会跟着一跳一跳;它们还会挤占同一个
 *   待发队列的额度。实时活动的状态推进由服务自己负责(它醒着),闹钟只负责把课上屏。
 * - **静音**:「取消本节课提醒」按「课程:日期」记到下课为止,重试与重启后依然生效。
 * - **日程提醒**:日程条目自带开始/结束绝对时刻,不像课程要靠作息换算;它的提醒用独立的
 *   action 排程(见 [ACTION_AGENDA_REMIND]),与课程闹钟天然不互相覆盖。
 */
@Singleton
class ReminderPlanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: CourseDao,
    private val agendaDao: AgendaDao,
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
            val agenda = agendaDao.getAll().map { it.toDomain() }
            val today = LocalDate.now()
            val signature = signature(courses, agenda, settings, today)
            val unchanged = !force && prefs.getString(KEY_SIGNATURE, null) == signature
            if (unchanged) {
                return@runBlocking
            }
            scheduleWindow(courses, agenda, settings, today)
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

    /** 取消某条日程已排的提醒(日程被删除时调用)。 */
    fun cancelAgenda(eventId: String) {
        val key = agendaKey(eventId)
        val kept = mutableListOf<String>()
        ledger().forEach { entry ->
            if (entry.key == key) {
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

    fun saveLive(payload: LiveUpdate) {
        prefs.edit().putString(KEY_LIVE, payload.toJson().toString()).apply()
    }

    fun restoreLive(): LiveUpdate? = prefs.getString(KEY_LIVE, null)?.let { raw ->
        runCatching { LiveUpdate.fromJson(JSONObject(raw)) }.getOrNull()
            ?.takeIf { it.segments.isNotEmpty() }
    }

    fun clearLive() {
        prefs.edit().remove(KEY_LIVE).apply()
    }

    // —— 内部:排程 ——

    private fun scheduleWindow(
        courses: List<Course>,
        agenda: List<AgendaEvent>,
        settings: AppSettings,
        today: LocalDate,
    ) {
        cancelLedger()
        val now = System.currentTimeMillis()
        val lead = settings.notifyLeadMinutes.coerceIn(0, 180)
        val periods = Schedule.parsePeriods(settings.periodTimes)
        val zone = ZoneId.systemDefault()
        val live = settings.notifyMode == NotifyMode.LIVE.name
        val entries = mutableListOf<Entry>()
        // 调休:提醒与免打扰都必须和课表看到的一致 —— 停课日不排,补课日按原课程日期那天的课排
        val adjustments = Adjustments.decode(settings.scheduleAdjustments)

        if (settings.notificationsEnabled) for (offset in 0 until HORIZON_DAYS) {
            val date = today.plusDays(offset.toLong())
            val teaching = Adjustments.teachingDay(
                adjustments,
                date,
                settings.semesterStartDay,
                settings.semesterWeekCount,
            )
            val dayCourses = if (teaching == null) {
                emptyList()
            } else {
                courses.filter { it.isOnWeekday(teaching.second) && it.isActiveOnWeek(teaching.first) }
            }

            dayCourses.forEach { course ->
                val startMinute = Schedule.courseStartMinute(course, periods) ?: return@forEach
                val endMinute = Schedule.courseEndMinute(course, periods)
                    ?: (startMinute + Schedule.PERIOD_LENGTH_MIN)
                val startAt = millisAt(date, startMinute, zone)
                val endAt = millisAt(date, endMinute, zone)
                if (endAt <= now) return@forEach
                val key = "${course.id}:${date.toEpochDay()}"
                val trigger = startAt - lead * 60_000L
                if (trigger <= now || trigger >= endAt) return@forEach
                val segments = LiveUpdate.segmentsOf(course, date, periods, zone)
                if (segments.isEmpty()) return@forEach
                val payload = LiveUpdate(
                    courseId = course.id,
                    name = course.name,
                    location = course.location,
                    timeText = Schedule.courseTimeText(course, periods),
                    muteKey = key,
                    segments = segments,
                )
                val code = eventCode(date.toEpochDay(), course.id, LIVE_INDEX)
                scheduleAlarm(
                    trigger = trigger,
                    requestCode = code,
                    intent = reminderIntent(payload, live),
                )
                entries += Entry(code, ACTION_REMIND, course.id)
                // 实时活动模式:每个相位边界(上课 / 课间 / 下课)再排一个闹钟。息屏时协程的
                // delay 不按墙上时间推进,靠这些边界闹钟把相变补齐(服务醒着时自己也会到点刷新)。
                if (live) {
                    payload.refreshBoundaries()
                        .filter { it > now && it != trigger }
                        .forEachIndexed { index, boundary ->
                            val boundaryCode =
                                eventCode(date.toEpochDay(), course.id, LIVE_BOUNDARY_INDEX + index)
                            scheduleAlarm(boundary, boundaryCode, reminderIntent(payload, live))
                            entries += Entry(boundaryCode, ACTION_REMIND, course.id)
                        }
                }
            }

            // 明日课程预告(可选):前一天指定时刻提醒
            if (settings.tomorrowReminderEnabled && offset in 0 until HORIZON_DAYS - 1) {
                val nextDate = date.plusDays(1)
                val nextTeaching = Adjustments.teachingDay(
                    adjustments,
                    nextDate,
                    settings.semesterStartDay,
                    settings.semesterWeekCount,
                )
                val nextCourses = if (nextTeaching == null) {
                    emptyList()
                } else {
                    courses.filter {
                        it.isOnWeekday(nextTeaching.second) && it.isActiveOnWeek(nextTeaching.first)
                    }
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

        if (settings.agendaReminderEnabled) {
            scheduleAgenda(agenda, settings, today, zone, now, entries)
        }

        if (settings.classDndEnabled) {
            scheduleDnd(courses, settings, today, periods, zone, adjustments, entries)
        }

        // 自续期:次日 00:05 强制重排,把窗口整体往前滚(不依赖 App 被打开)。
        // 提醒与免打扰都关掉时不必留这个闹钟 —— 排它也没有任何事要做。
        if (settings.notificationsEnabled || settings.classDndEnabled || settings.agendaReminderEnabled) {
            val maintenanceAt = today.plusDays(1).atTime(0, 5)
                .atZone(zone).toInstant().toEpochMilli()
            val maintenanceCode = eventCode(today.plusDays(1).toEpochDay(), REFRESH_KEY, 99)
            scheduleAlarm(
                trigger = maintenanceAt,
                requestCode = maintenanceCode,
                intent = Intent(context, CourseReminderReceiver::class.java).setAction(ACTION_REFRESH),
            )
            entries += Entry(maintenanceCode, ACTION_REFRESH, REFRESH_KEY)
        }

        saveLedger(entries)
        Log.i(
            TAG,
            "已排 ${entries.size} 个闹钟(窗口 $HORIZON_DAYS 天," +
                "提醒=${settings.notificationsEnabled} 模式=${settings.notifyMode}," +
                "免打扰=${settings.classDndEnabled})",
        )
    }

    /**
     * 上课自动免打扰:每节课排两个闹钟 —— 上课进、下课退。
     *
     * 与提醒分开开关:提醒关掉的人照样可以只用免打扰。时刻一律取课程自己的时间
     * (自定义时间课程按它自己的起止),不再另外加提前量。
     */
    private fun scheduleDnd(
        courses: List<Course>,
        settings: AppSettings,
        today: LocalDate,
        periods: List<Schedule.Period>,
        zone: ZoneId,
        adjustments: List<ScheduleAdjustment>,
        entries: MutableList<Entry>,
    ) {
        val now = System.currentTimeMillis()
        for (offset in 0 until HORIZON_DAYS) {
            val date = today.plusDays(offset.toLong())
            val teaching = Adjustments.teachingDay(
                adjustments,
                date,
                settings.semesterStartDay,
                settings.semesterWeekCount,
            ) ?: continue
            courses
                .filter { it.isOnWeekday(teaching.second) && it.isActiveOnWeek(teaching.first) }
                .forEach { course ->
                    val startMinute = Schedule.courseStartMinute(course, periods) ?: return@forEach
                    val endMinute = Schedule.courseEndMinute(course, periods)
                        ?: (startMinute + Schedule.PERIOD_LENGTH_MIN)
                    val startAt = millisAt(date, startMinute, zone)
                    val endAt = millisAt(date, endMinute, zone)
                    if (startAt > now) {
                        val code = eventCode(date.toEpochDay(), course.id, DND_ON_INDEX)
                        scheduleAlarm(startAt, code, dndIntent(ACTION_DND_ON))
                        entries += Entry(code, ACTION_DND_ON, course.id)
                    }
                    if (endAt > now) {
                        val code = eventCode(date.toEpochDay(), course.id, DND_OFF_INDEX)
                        scheduleAlarm(endAt, code, dndIntent(ACTION_DND_OFF))
                        entries += Entry(code, ACTION_DND_OFF, course.id)
                    }
                }
        }
    }

    private fun dndIntent(action: String): Intent =
        Intent(context, DndReceiver::class.java).setAction(action)

    /**
     * 日程提醒:日程自带绝对起止时刻,排程比课程直接 —— 定时的在开始前
     * [AgendaEvent.remindLeadMinutes] 分钟触发;全天的在该天设置里的固定时刻触发(默认 09:00)。
     *
     * 用独立 action([ACTION_AGENDA_REMIND])排:与课程闹钟的 PendingIntent 身份(action 不同)
     * 天然区分,即使 requestCode 撞了也不会互相覆盖。
     */
    private fun scheduleAgenda(
        agenda: List<AgendaEvent>,
        settings: AppSettings,
        today: LocalDate,
        zone: ZoneId,
        now: Long,
        entries: MutableList<Entry>,
    ) {
        val allDayMinute = Schedule.parseClock(settings.agendaAllDayRemindTime)
            ?: DEFAULT_AGENDA_ALLDAY_MINUTE
        val windowEnd = today.plusDays(HORIZON_DAYS.toLong())
            .atStartOfDay(zone).toInstant().toEpochMilli()
        agenda.filter { it.remindEnabled }.forEach { event ->
            val trigger = if (event.allDay) {
                val day = Instant.ofEpochMilli(event.startAt).atZone(zone).toLocalDate()
                millisAt(day, allDayMinute, zone)
            } else {
                event.startAt - event.remindLeadMinutes.coerceAtLeast(0) * 60_000L
            }
            if (trigger <= now || trigger >= windowEnd) return@forEach
            val key = agendaKey(event.id)
            val code = eventCode(
                Instant.ofEpochMilli(trigger).atZone(zone).toLocalDate().toEpochDay(),
                key,
                AGENDA_INDEX,
            )
            scheduleAlarm(
                trigger = trigger,
                requestCode = code,
                intent = Intent(context, CourseReminderReceiver::class.java)
                    .setAction(ACTION_AGENDA_REMIND)
                    .putExtra(EXTRA_AGENDA_ID, event.id),
            )
            entries += Entry(code, ACTION_AGENDA_REMIND, key)
        }
    }

    private fun reminderIntent(payload: LiveUpdate, live: Boolean): Intent =
        Intent(context, CourseReminderReceiver::class.java)
            .setAction(ACTION_REMIND)
            .putExtra(EXTRA_LIVE, live)
            .putExtra(EXTRA_COURSE_ID, payload.courseId)
            .putExtra(EXTRA_COURSE_NAME, payload.name)
            .putExtra(EXTRA_LOCATION, payload.location)
            .putExtra(EXTRA_TIME_TEXT, payload.timeText)
            .putExtra(EXTRA_MUTE_KEY, payload.muteKey)
            .putExtra(EXTRA_SEGMENTS, encodeLiveSegments(payload.segments))

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

    private fun signature(
        courses: List<Course>,
        agenda: List<AgendaEvent>,
        s: AppSettings,
        today: LocalDate,
    ): String {
        val coursePart = courses.sortedBy { it.id }.joinToString(";") { c ->
            listOf(
                c.id, c.name, c.location, c.teacher,
                c.weekday, c.weekdays, "${c.startPeriod}-${c.endPeriod}",
                c.weekType.name, c.weekStart, c.weekEnd, c.weeks.joinToString(","),
                c.customStartMinute ?: "-", c.customEndMinute ?: "-",
            ).joinToString(":")
        }
        // 只把「还没结束」的日程算进签名 —— 已经过去的条目再也不会排闹钟,改它不该触发重排
        val todayStart = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val agendaPart = agenda
            .filter { it.endAt >= todayStart }
            .sortedBy { it.id }
            .joinToString(";") { e ->
                listOf(
                    e.id, e.title, e.location, e.allDay,
                    e.startAt, e.endAt, e.remindEnabled, e.remindLeadMinutes,
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
            // 日程提醒同理必须进签名:否则开了这个开关、签名没变,排程会直接 early return
            s.agendaReminderEnabled,
            s.agendaAllDayRemindTime,
            // 免打扰必须进签名:否则开了这个开关、签名没变,排程会直接 early return,一节都不排
            s.classDndEnabled,
            // 调休必须进签名:否则改了调休、签名没变,这里会直接 early return,提醒还是旧的
            s.scheduleAdjustments,
            coursePart,
            agendaPart,
        ).joinToString("|")
    }

    private fun eventCode(epochDay: Long, courseKey: String, index: Int): Int =
        (listOf(epochDay, courseKey, index).hashCode()) and Int.MAX_VALUE

    private fun agendaKey(eventId: String): String = "agenda:$eventId"

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
        private const val SIGNATURE_VERSION = "plan-v5"

        /** 滚动窗口天数(含今天)。 */
        const val HORIZON_DAYS = 8

        const val ACTION_REMIND = "com.kxin.classtable.ACTION_COURSE_REMIND"
        const val ACTION_REFRESH = "com.kxin.classtable.ACTION_REFRESH_REMINDERS"
        /** 日程到点提醒(与课程提醒分开的 action,闹钟互不覆盖)。 */
        const val ACTION_AGENDA_REMIND = "com.kxin.classtable.ACTION_AGENDA_REMIND"
        /** 上课自动免打扰:上课进、下课退。 */
        const val ACTION_DND_ON = "com.kxin.classtable.ACTION_DND_ON"
        const val ACTION_DND_OFF = "com.kxin.classtable.ACTION_DND_OFF"

        const val EXTRA_LIVE = "live"
        const val EXTRA_COURSE_ID = "course_id"
        const val EXTRA_COURSE_NAME = "course_name"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_TIME_TEXT = "time_text"
        const val EXTRA_SEGMENTS = "segments"
        const val EXTRA_START_MINUTE = "start_minute"
        const val EXTRA_MUTE_KEY = "mute_key"
        const val EXTRA_TOMORROW = "tomorrow"
        const val EXTRA_TOMORROW_COUNT = "tomorrow_count"
        const val EXTRA_AGENDA_ID = "agenda_id"

        const val TOMORROW_KEY = "tomorrow"
        const val REFRESH_KEY = "refresh"

        /** 课程提醒的 requestCode 序号。一节课只有一个「上屏」闹钟(不再有 1/3/5 分钟的重试)。 */
        private const val LIVE_INDEX = 0

        /** 实时活动相位边界闹钟的 requestCode 序号起点(每个边界 +1)。 */
        private const val LIVE_BOUNDARY_INDEX = 20

        /** 免打扰两个闹钟的 requestCode 序号:与课程提醒(0)、相变(20+)、自续期(99)错开。 */
        private const val DND_ON_INDEX = 5
        private const val DND_OFF_INDEX = 6

        /** 日程提醒的 requestCode 序号:与课程提醒、免打扰、自续期都错开。 */
        private const val AGENDA_INDEX = 7

        private const val DEFAULT_TOMORROW_MINUTE = 21 * 60 + 30

        /** 全天日程默认提醒时刻:09:00(可在「设置 → 提醒 → 日程提醒」改)。 */
        private const val DEFAULT_AGENDA_ALLDAY_MINUTE = 9 * 60

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


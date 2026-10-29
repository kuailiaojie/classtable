package com.kxin.classtable.data

import com.kxin.classtable.domain.WeekSpec
import com.kxin.classtable.domain.enumOrDefault
import com.kxin.classtable.domain.model.AgendaCategory
import com.kxin.classtable.domain.model.AgendaEvent
import com.kxin.classtable.domain.model.AgendaPriority
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.WeekType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地全量数据离线备份与恢复管理器。
 *
 * 支持将全部课程、日程待办与基础学期设置序列化为独立的 `.classtable.json` 文件，
 * 满足无账号、无网络或隐私敏感用户的安全导出与设备间无缝还原。
 */
@Singleton
class BackupManager @Inject constructor(
    private val courseRepository: CourseRepository,
    private val agendaRepository: AgendaRepository,
    private val settingsRepository: SettingsRepository,
) {
    data class BackupSummary(
        val courseCount: Int,
        val agendaCount: Int,
        val exportedAt: Long,
    )

    /**
     * 生成全量备份的 JSON 字符串。
     */
    suspend fun createBackupJson(): String = withContext(Dispatchers.IO) {
        val courses = courseRepository.getAll()
        val agenda = agendaRepository.getAll()
        val settings = settingsRepository.settings.first()

        val root = JSONObject()
        root.put("app", "YohakuClasstable")
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())

        val coursesArray = JSONArray()
        for (c in courses) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("name", c.name)
            o.put("teacher", c.teacher)
            o.put("location", c.location)
            o.put("weekday", c.weekday)
            o.put("weekdays", c.weekdays)
            o.put("startPeriod", c.startPeriod)
            o.put("endPeriod", c.endPeriod)
            o.put("weekType", c.weekType.name)
            o.put("weekStart", c.weekStart)
            o.put("weekEnd", c.weekEnd)
            o.put("weeks", WeekSpec.encode(c.weeks))
            o.put("semesterId", c.semesterId)
            o.put("customStartMinute", c.customStartMinute ?: JSONObject.NULL)
            o.put("customEndMinute", c.customEndMinute ?: JSONObject.NULL)
            o.put("note", c.note)
            o.put("colorHex", c.colorHex)
            o.put("colorHue", c.colorHue ?: JSONObject.NULL)
            o.put("updatedAt", c.updatedAt)
            coursesArray.put(o)
        }
        root.put("courses", coursesArray)

        val agendaArray = JSONArray()
        for (a in agenda) {
            val o = JSONObject()
            o.put("id", a.id)
            o.put("title", a.title)
            o.put("category", a.category.name)
            o.put("startAt", a.startAt)
            o.put("endAt", a.endAt)
            o.put("allDay", a.allDay)
            o.put("location", a.location)
            o.put("note", a.note)
            o.put("priority", a.priority.name)
            o.put("remindEnabled", a.remindEnabled)
            o.put("remindLeadMinutes", a.remindLeadMinutes)
            o.put("updatedAt", a.updatedAt)
            o.put("courseId", a.courseId ?: JSONObject.NULL)
            o.put("completed", a.completed)
            agendaArray.put(o)
        }
        root.put("agenda", agendaArray)

        val settingsObj = JSONObject()
        settingsObj.put("semesterStartDay", settings.semesterStartDay)
        settingsObj.put("semesterWeekCount", settings.semesterWeekCount)
        settingsObj.put("periodTimes", settings.periodTimes)
        settingsObj.put("scheduleAdjustments", settings.scheduleAdjustments)
        root.put("settings", settingsObj)

        root.toString(2)
    }

    /**
     * 将全量备份写入给定输出流。
     */
    suspend fun writeBackupToStream(outputStream: OutputStream) = withContext(Dispatchers.IO) {
        val json = createBackupJson()
        outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(json) }
    }

    /**
     * 从输入流读取并恢复课程、日程与学期配置。
     */
    suspend fun restoreFromStream(inputStream: InputStream): Result<BackupSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val text = inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val root = JSONObject(text)
            if (!root.has("courses") && !root.has("agenda")) {
                throw IllegalArgumentException("所选文件格式不正确，未检测到余课备份数据")
            }

            val courses = mutableListOf<Course>()
            val coursesArray = root.optJSONArray("courses")
            if (coursesArray != null) {
                for (i in 0 until coursesArray.length()) {
                    val o = coursesArray.getJSONObject(i)
                    val wType = enumOrDefault(o.optString("weekType"), WeekType.EVERY_WEEK)
                    val c = Course(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        teacher = o.optString("teacher", ""),
                        location = o.optString("location", ""),
                        weekday = o.optInt("weekday", 1),
                        startPeriod = o.optInt("startPeriod", 1),
                        endPeriod = o.optInt("endPeriod", 1),
                        weekType = wType,
                        weekStart = o.optInt("weekStart", 1),
                        weekEnd = o.optInt("weekEnd", 16),
                        weeks = WeekSpec.decode(o.optString("weeks", "")),
                        semesterId = o.optString("semesterId", "default"),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                        customStartMinute = if (o.isNull("customStartMinute")) null else o.optInt("customStartMinute"),
                        customEndMinute = if (o.isNull("customEndMinute")) null else o.optInt("customEndMinute"),
                        weekdays = o.optInt("weekdays", 1 shl (o.optInt("weekday", 1) - 1)),
                        note = o.optString("note", ""),
                        colorHex = o.optString("colorHex", ""),
                        colorHue = if (o.isNull("colorHue")) null else o.optInt("colorHue"),
                    )
                    courses.add(c)
                }
            }

            val agendaList = mutableListOf<AgendaEvent>()
            val agendaArray = root.optJSONArray("agenda")
            if (agendaArray != null) {
                for (i in 0 until agendaArray.length()) {
                    val o = agendaArray.getJSONObject(i)
                    val cat = enumOrDefault(o.optString("category"), AgendaCategory.TODO)
                    val prio = enumOrDefault(o.optString("priority"), AgendaPriority.NONE)
                    val courseIdVal = if (o.isNull("courseId")) null else o.optString("courseId").takeIf { it.isNotBlank() }
                    val completedVal = o.optBoolean("completed", false)
                    val a = AgendaEvent(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        category = cat,
                        startAt = o.optLong("startAt"),
                        endAt = o.optLong("endAt"),
                        allDay = o.optBoolean("allDay"),
                        location = o.optString("location", ""),
                        note = o.optString("note", ""),
                        priority = prio,
                        remindEnabled = o.optBoolean("remindEnabled", false),
                        remindLeadMinutes = o.optInt("remindLeadMinutes", 10),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                        courseId = courseIdVal,
                        completed = completedVal,
                    )
                    agendaList.add(a)
                }
            }

            val settingsObj = root.optJSONObject("settings")
            if (settingsObj != null) {
                val startDay = settingsObj.optLong("semesterStartDay", 0L)
                val weekCount = settingsObj.optInt("semesterWeekCount", 20)
                if (startDay > 0L) {
                    settingsRepository.setSemester(startDay, weekCount)
                }
                val periods = settingsObj.optString("periodTimes")
                if (periods.isNotBlank()) {
                    settingsRepository.setPeriodTimes(periods)
                }
                val adj = settingsObj.optString("scheduleAdjustments")
                if (adj.isNotBlank()) {
                    settingsRepository.setScheduleAdjustments(adj)
                }
            }

            if (courses.isNotEmpty()) {
                courseRepository.restoreAll(courses)
            }
            if (agendaList.isNotEmpty()) {
                agendaRepository.importAll(agendaList)
            }

            BackupSummary(
                courseCount = courses.size,
                agendaCount = agendaList.size,
                exportedAt = root.optLong("exportedAt", System.currentTimeMillis()),
            )
        }
    }
}

package com.kxin.classtable.ui.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.importer.XlsxParser
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.WeekType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.Charset
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ManualImportViewModel @Inject constructor(
    private val courseRepository: CourseRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _imported = MutableStateFlow(false)
    val imported: StateFlow<Boolean> = _imported.asStateFlow()

    fun importAll(courses: List<Course>, periodSpec: String?) {
        viewModelScope.launch {
            if (!periodSpec.isNullOrBlank()) {
                settingsRepository.setPeriodTimes(periodSpec)
            }
            courseRepository.importAll(courses)
            _imported.value = true
        }
    }
}

/**
 * 手动表格导入:粘贴表格数据(CSV/TSV),或直接从 Excel(xlsx)/CSV/TSV 文件导入。
 * 列序:课程名称,教师,地点,星期(1-7或一~日),开始节,结束节,周次。
 */
@Composable
fun ManualImportScreen(
    nav: NavHostController,
    viewModel: ManualImportViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val context = LocalContext.current
    val imported by viewModel.imported.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    val parsed = remember(text) { parseTable(text) }
    var fileCourses by remember { mutableStateOf<List<Course>>(emptyList()) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var scheduleText by rememberSaveable { mutableStateOf("") }
    val parsedSchedule = remember(scheduleText) { parseScheduleLines(scheduleText) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri)
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                fileError = "无法读取所选文件"
            } else if (name.endsWith(".xls", ignoreCase = true) && !name.endsWith(".xlsx", ignoreCase = true)) {
                fileError = "暂不支持旧版 .xls 格式,请在 Excel 中另存为 .xlsx 或 CSV 后再导入"
                fileCourses = emptyList()
                fileName = name
            } else {
                val rows = when {
                    name.endsWith(".xlsx", ignoreCase = true) -> XlsxParser.parse(bytes)
                    else -> decodeText(bytes).lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .let { lines ->
                            val sep = if (lines.any { it.contains('\t') }) '\t' else ','
                            lines.map { line -> line.split(sep).map { it.trim() } }
                        }
                }
                val courses = rowsToCourses(rows)
                fileCourses = courses
                fileName = name
                fileError = if (courses.isEmpty()) "文件中没有解析到课程,请检查列序" else null
            }
        }
    }

    val allCourses = fileCourses + parsed.courses

    LaunchedEffect(imported) { if (imported) nav.popBackStack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(title = "手动表格导入", onBack = { nav.popBackStack() })

        Column(modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding)) {
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
            Text(
                text = "每行一门课,用逗号或 Tab 分隔,列序:",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Text(
                text = "课程名称, 教师, 地点, 星期(1-7或一~日), 开始节, 结束节, 周次",
                style = YohakuType.timeMono,
                color = colors.neutral7,
            )
            Text(
                text = "周次可填:每周 / 单周 / 双周 / 1-16 / 1,3,5",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Text(
                text = "若在第 8、9 列填开始/结束时间(如 18:30),则为自定义时间课程,不随作息表。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
            Text(
                text = "从 Excel 文件导入(.xlsx / .csv / .tsv)",
                style = YohakuType.copy13,
                color = colors.accent,
                modifier = Modifier
                    .clickable {
                        filePicker.launch(
                            arrayOf(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.ms-excel",
                                "text/csv",
                                "text/tab-separated-values",
                                "text/plain",
                                "application/octet-stream",
                            ),
                        )
                    }
                    .padding(vertical = 4.dp),
            )
            if (fileName != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "文件: $fileName · 解析到 ${fileCourses.size} 门课",
                    style = YohakuType.label12,
                    color = colors.neutral7,
                )
            }
            if (fileError != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = fileError!!, style = YohakuType.label12, color = colors.error)
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
            Text(
                text = "作息时间(选填):一行一节,填起止时间,如 08:00-08:50,导入时一键同步",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            BasicTextField(
                value = scheduleText,
                onValueChange = { scheduleText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .background(colors.neutral2, RoundedCornerShape(YohakuDimens.radiusCard))
                    .padding(10.dp),
                textStyle = YohakuType.copy14.copy(color = colors.neutral9),
                cursorBrush = SolidColor(colors.accent),
                decorationBox = { inner ->
                    if (scheduleText.isEmpty()) {
                        Text(
                            text = "08:00-08:50\n09:00-09:50\n10:10-11:00",
                            style = YohakuType.copy14,
                            color = colors.neutral5,
                        )
                    }
                    inner()
                },
            )
            if (scheduleText.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                if (parsedSchedule != null) {
                    Text(
                        text = "识别到 ${parsedSchedule.size} 节作息,导入课程时一并同步",
                        style = YohakuType.label12,
                        color = colors.accent,
                    )
                } else {
                    Text(
                        text = "作息格式无法解析,示例:08:00-08:50(一行一节)",
                        style = YohakuType.label12,
                        color = colors.error,
                    )
                }
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(colors.neutral2, RoundedCornerShape(YohakuDimens.radiusCard))
                    .padding(12.dp),
                textStyle = YohakuType.copy14.copy(color = colors.neutral9),
                cursorBrush = SolidColor(colors.accent),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text(
                            text = "示例:\n高等数学,张老师,教1-201,一,1,2,每周\n大学英语,李老师,外语楼204,三,3,4,1-16",
                            style = YohakuType.copy14,
                            color = colors.neutral5,
                        )
                    }
                    inner()
                },
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
            Text(
                text = if (allCourses.isEmpty()) "暂未解析到课程" else "共解析到 ${allCourses.size} 门课",
                style = YohakuType.copy13,
                color = if (allCourses.isEmpty()) colors.neutral7 else colors.neutral9,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = YohakuDimens.screenPadding),
        ) {
            items(allCourses, key = { it.id }) { course ->
                Row(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = Course.weekdaysShort(course),
                        style = YohakuType.timeMono,
                        color = colors.neutral7,
                        modifier = Modifier.width(44.dp),
                    )
                    Text(
                        text = Schedule.courseTimeText(course),
                        style = YohakuType.timeMono,
                        color = colors.neutral7,
                        modifier = Modifier.width(90.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = course.name, style = YohakuType.copy14, color = colors.neutral10)
                        val meta = listOf(course.teacher, course.location, weekText(course))
                            .filter { it.isNotBlank() }.joinToString(" · ")
                        if (meta.isNotEmpty()) {
                            Text(text = meta, style = YohakuType.label12, color = colors.neutral7)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(YohakuDimens.screenPadding),
        ) {
            YohakuButton(
                text = "导入 ${allCourses.size} 门课",
                onClick = {
                    viewModel.importAll(
                        allCourses,
                        parsedSchedule?.let { Schedule.serializePeriods(it) },
                    )
                },
                enabled = allCourses.isNotEmpty(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    return context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        ?: uri.lastPathSegment
        ?: "file"
}

/** 文本解码:去 BOM,UTF-8 出现乱码时回退 GBK(中文 Excel CSV 常见)。 */
private fun decodeText(bytes: ByteArray): String {
    var offset = 0
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        offset = 3
    }
    val utf8 = String(bytes, offset, bytes.size - offset, Charsets.UTF_8)
    return if (utf8.contains('\uFFFD')) {
        runCatching { String(bytes, offset, bytes.size - offset, Charset.forName("GBK")) }.getOrDefault(utf8)
    } else {
        utf8
    }
}

private data class TableParse(val courses: List<Course>)

private fun parseTable(raw: String): TableParse {
    val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return TableParse(emptyList())
    val sep = if (lines.any { it.contains('\t') }) '\t' else ','
    val rows = lines.map { line -> line.split(sep).map { it.trim() } }
    return TableParse(rowsToCourses(rows))
}

/** 行数据 → 课程列表(自动跳过含「课程/名称」的表头行)。 */
private fun rowsToCourses(rows: List<List<String>>): List<Course> {
    val data = if (rows.firstOrNull()?.any { it.contains("课程") || it.contains("名称") } == true) {
        rows.drop(1)
    } else {
        rows
    }
    return data.mapNotNull { row ->
        if (row.size < 4) return@mapNotNull null
        val name = row[0]
        val weekday = parseWeekday(row[3]) ?: return@mapNotNull null
        val start = row.getOrElse(4) { "" }.toIntOrNull() ?: 1
        val end = row.getOrElse(5) { "" }.toIntOrNull() ?: start
        val (weekType, ws, we) = parseWeeks(row.getOrElse(6) { "每周" })
        val cs = row.getOrElse(7) { "" }.trim().let { Schedule.parseClock(it) }
        val ce = row.getOrElse(8) { "" }.trim().let { Schedule.parseClock(it) }
        val custom = cs != null && ce != null && ce > cs
        Course(
            id = "manual-${UUID.randomUUID()}",
            name = name,
            teacher = row.getOrElse(1) { "" },
            location = row.getOrElse(2) { "" },
            weekday = weekday,
            startPeriod = if (custom) 0 else start,
            endPeriod = if (custom) 0 else end.coerceAtLeast(start),
            weekType = weekType,
            weekStart = ws,
            weekEnd = we,
            customStartMinute = if (custom) cs else null,
            customEndMinute = if (custom) ce else null,
        )
    }
}

private fun parseWeekday(s: String): Int? {
    val t = s.trim()
    val map = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "日" to 7, "天" to 7,
        "周一" to 1, "周二" to 2, "周三" to 3, "周四" to 4, "周五" to 5, "周六" to 6, "周日" to 7,
        "星期一" to 1, "星期二" to 2, "星期三" to 3, "星期四" to 4, "星期五" to 5, "星期六" to 6, "星期日" to 7,
    )
    return map[t] ?: t.toIntOrNull()?.takeIf { it in 1..7 }
}

private fun parseWeeks(s: String): Triple<WeekType, Int, Int> {
    val t = s.trim()
    return when {
        t.contains("单") -> Triple(WeekType.ODD_WEEK, 1, 16)
        t.contains("双") -> Triple(WeekType.EVEN_WEEK, 1, 16)
        t.contains("-") -> {
            val p = t.split("-")
            Triple(
                WeekType.CUSTOM,
                p.getOrNull(0)?.toIntOrNull() ?: 1,
                p.getOrNull(1)?.toIntOrNull() ?: 16,
            )
        }
        t.contains(",") -> {
            val ws = t.split(",").mapNotNull { it.toIntOrNull() }.filter { it > 0 }
            val wt = when {
                ws.all { it % 2 == 1 } -> WeekType.ODD_WEEK
                ws.all { it % 2 == 0 } -> WeekType.EVEN_WEEK
                else -> WeekType.CUSTOM
            }
            Triple(wt, ws.minOrNull() ?: 1, ws.maxOrNull() ?: 16)
        }
        else -> Triple(WeekType.EVERY_WEEK, 1, 16)
    }
}

/** 作息文本 → 时间段列表。支持 "08:00-08:50" 或 "1 08:00 08:50" 一行一节;任一无法解析返回 null。 */
private fun parseScheduleLines(text: String): List<Schedule.Period>? {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    val out = mutableListOf<Schedule.Period>()
    for (line in lines) {
        val period = if (line.count { it == '-' } == 1) {
            val parts = line.split('-')
            val s = Schedule.parseClock(parts[0])
            val e = Schedule.parseClock(parts[1])
            if (s != null && e != null && e > s) Schedule.Period(s, e) else return null
        } else {
            val parts = line.split(Regex("\\s+"))
            val timeParts = if (parts.size >= 3) listOf(parts[1], parts[2]) else parts
            val s = Schedule.parseClock(timeParts.getOrNull(0) ?: "")
            val e = Schedule.parseClock(timeParts.getOrNull(1) ?: "")
            if (s != null && e != null && e > s) Schedule.Period(s, e) else return null
        }
        out.add(period)
    }
    return out.distinctBy { it.start }.sortedBy { it.start }
}

private fun weekText(course: Course): String = when (course.weekType) {
    WeekType.EVERY_WEEK -> "每周"
    WeekType.ODD_WEEK -> "单周"
    WeekType.EVEN_WEEK -> "双周"
    WeekType.CUSTOM -> "第${course.weekStart}-${course.weekEnd}周"
}

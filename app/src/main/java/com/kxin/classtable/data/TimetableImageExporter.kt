package com.kxin.classtable.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.kxin.classtable.R
import com.kxin.classtable.design.CoursePalette
import com.kxin.classtable.domain.Adjustments
import com.kxin.classtable.domain.Dates
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.Course
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Yohaku 美学课表长图导出器。
 *
 * 将指定周次的整周课表渲染为艺术海报级高清纸感长图（PNG），
 * 严格对齐课程在界面中的 OKLCh 淡彩与和色标，保留思源宋体排版、
 * 朱砂落款印与留白美学，支持保存至系统相册或系统多渠道分享。
 */
@Singleton
class TimetableImageExporter @Inject constructor(
    private val courseRepository: CourseRepository,
    private val settingsRepository: SettingsRepository,
    @param:ApplicationContext private val context: Context,
) {
    private data class GridBlock(
        val course: Course,
        val top: Float,
        val height: Float,
        val lane: Int = 0,
    )

    /**
     * 生成高清艺术周课表 Bitmap。
     */
    suspend fun generateBitmap(
        week: Int,
        specifiedVisibleDays: List<Int>? = null,
    ): Bitmap = withContext(Dispatchers.Default) {
        val courses = courseRepository.getAll()
        val settings = settingsRepository.settings.first()
        val timetablePrefs = TimetablePrefsStore.flow(context).first()
        val scheme = timetablePrefs.colorScheme
        val adjustments = Adjustments.decode(settings.scheduleAdjustments)
        val periods = Schedule.parsePeriods(settings.periodTimes)

        val serifTypeface = ResourcesCompat.getFont(context, R.font.noto_serif_sc_medium)
            ?: Typeface.SERIF
        val monoTypeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular)
            ?: Typeface.MONOSPACE

        // 解析该周的实际上课情况
        val teachingDays = (1..7).map { d ->
            val dates = Schedule.weekDates(settings.semesterStartDay, week)
            val date = dates.getOrNull(d - 1)
            if (date != null) {
                Adjustments.teachingDay(adjustments, date, settings.semesterStartDay, settings.semesterWeekCount)
            } else {
                week to d
            }
        }

        // 确定可见星期（根据设置支持 5 天或 7 天）
        val visibleDays = specifiedVisibleDays ?: run {
            when (timetablePrefs.weekendDisplay) {
                WeekendDisplay.HIDE_WEEKEND -> (1..5).toList()
                WeekendDisplay.SHOW_ALL -> (1..7).toList()
                WeekendDisplay.AUTO_HIDE -> {
                    val weekendHasClass = listOf(6, 7).any { d ->
                        val teaching = teachingDays.getOrNull(d - 1)
                        teaching != null && courses.any {
                            it.isOnWeekday(teaching.second) && it.isActiveOnWeek(teaching.first)
                        }
                    }
                    if (weekendHasClass) (1..7).toList() else (1..5).toList()
                }
            }
        }

        // 艺术画报画布尺寸规范
        val canvasWidth = 1400
        val marginH = 64f
        val headerHeight = 260f
        val weekdayBarHeight = 100f
        val gutterWidth = 100f
        val rowHeight = 135f
        val footerHeight = 140f
        val gridHeight = periods.size * rowHeight
        val totalHeight = (headerHeight + weekdayBarHeight + gridHeight + footerHeight).toInt()

        val bitmap = Bitmap.createBitmap(canvasWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. 暖纸底背景 (Yohaku paper #FAF8F5)
        val bgPaint = Paint().apply {
            color = android.graphics.Color.parseColor("#FAF8F5")
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, canvasWidth.toFloat(), totalHeight.toFloat(), bgPaint)

        // 艺术典雅外框 (Inner museum frame)
        val outerFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#EAE6DC")
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        canvas.drawRect(24f, 24f, canvasWidth - 24f, totalHeight - 24f, outerFramePaint)

        // 2. 顶部艺术排版与朱砂印章
        // 顶栏眉线
        val eyebrowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#9E9A90")
            textSize = 18f
            typeface = monoTypeface
            letterSpacing = 0.12f
        }
        canvas.drawText("—  Y O H A K U   ·   学 期 课 程 历  —", marginH, 80f, eyebrowPaint)

        // 主标题
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#181715")
            textSize = 54f
            typeface = serifTypeface
        }
        canvas.drawText("第 $week 周 课程表", marginH, 148f, titlePaint)

        // 朱砂印章 (落款印「余课」)
        val stampSize = 56f
        val stampRight = canvasWidth - marginH
        val stampTop = 86f
        val stampRect = RectF(stampRight - stampSize, stampTop, stampRight, stampTop + stampSize)
        val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#C56473") // 传统梅红/朱砂
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(stampRect, 8f, 8f, stampPaint)

        val stampInnerRect = RectF(stampRect.left + 3f, stampRect.top + 3f, stampRect.right - 3f, stampRect.bottom - 3f)
        val stampBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(160, 255, 255, 255)
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        canvas.drawRoundRect(stampInnerRect, 6f, 6f, stampBorderPaint)

        val stampTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            typeface = serifTypeface
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("余", stampRect.centerX(), stampRect.top + 24f, stampTextPaint)
        canvas.drawText("课", stampRect.centerX(), stampRect.top + 48f, stampTextPaint)

        // 副标题与元信息行
        val dateRange = Schedule.weekRangeText(settings.semesterStartDay, week)
        val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#5C5952")
            textSize = 22f
            typeface = monoTypeface
        }
        val subtitleText = buildString {
            if (dateRange.isNotEmpty()) append(dateRange).append("  ·  ")
            append("学期第 ").append(week).append(" 周")
            if (settings.semesterStartDay <= 0L) append(" (未设置开学日)")
        }
        canvas.drawText(subtitleText, marginH, 206f, subtitlePaint)

        // 本周课程统计胶囊
        val activeCourseCount = courses.count { it.isActiveOnWeek(week) }
        val countBadgeText = "本周共 $activeCourseCount 节课程"
        val countBadgeWidth = subtitlePaint.measureText(countBadgeText) + 36f
        val countBadgeRect = RectF(
            stampRight - countBadgeWidth,
            184f,
            stampRight,
            222f,
        )
        val countBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#EFECE4")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(countBadgeRect, 19f, 19f, countBadgeBgPaint)
        val countBadgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#68645C")
            textSize = 19f
            typeface = serifTypeface
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(countBadgeText, countBadgeRect.centerX(), countBadgeRect.centerY() + 7f, countBadgeTextPaint)

        // 顶部分隔细线
        val dividerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#E2DDD4")
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(marginH, headerHeight - 12f, canvasWidth - marginH, headerHeight - 12f, dividerLinePaint)

        // 3. 表头星期栏排版
        val colWidth = (canvasWidth - 2 * marginH - gutterWidth) / visibleDays.size
        val weekdayNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#181715")
            textSize = 28f
            typeface = serifTypeface
            textAlign = Paint.Align.CENTER
        }
        val weekdayEnPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#8E8A80")
            textSize = 15f
            typeface = monoTypeface
            textAlign = Paint.Align.CENTER
        }
        val dayNumPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#5E5B54")
            textSize = 19f
            typeface = monoTypeface
            textAlign = Paint.Align.CENTER
        }

        val weekDates = Schedule.weekDates(settings.semesterStartDay, week)
        val todayWeekday = Schedule.todayWeekday()
        val currentWeek = Schedule.currentWeek(settings.semesterStartDay, settings.semesterWeekCount)
        val isViewingCurrentWeek = week == currentWeek

        visibleDays.forEachIndexed { idx, d ->
            val cx = marginH + gutterWidth + colWidth * idx + colWidth / 2f
            val isTodayCol = isViewingCurrentWeek && d == todayWeekday

            // 若为今天，绘制柔和高亮底衬
            if (isTodayCol) {
                val todayHeaderRect = RectF(
                    marginH + gutterWidth + colWidth * idx + 2f,
                    headerHeight - 6f,
                    marginH + gutterWidth + colWidth * (idx + 1) - 2f,
                    headerHeight + weekdayBarHeight - 6f,
                )
                val todayHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.parseColor("#F2EFE8")
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(todayHeaderRect, 10f, 10f, todayHeaderPaint)
            }

            // 星期文字
            val weekdayText = Dates.weekdayLabel(d)
            canvas.drawText(weekdayText, cx, headerHeight + 34f, weekdayNamePaint)

            // 英文星期简称
            val enWeekday = when (d) {
                1 -> "MON"
                2 -> "TUE"
                3 -> "WED"
                4 -> "THU"
                5 -> "FRI"
                6 -> "SAT"
                7 -> "SUN"
                else -> ""
            }
            canvas.drawText(enWeekday, cx, headerHeight + 58f, weekdayEnPaint)

            // 日期文本
            val dateStr = weekDates.getOrNull(d - 1)?.let {
                "${it.monthValue.toString().padStart(2, '0')}/${it.dayOfMonth.toString().padStart(2, '0')}"
            } ?: ""
            if (dateStr.isNotEmpty()) {
                canvas.drawText(dateStr, cx, headerHeight + 84f, dayNumPaint)
            }
        }

        // 表头下分隔线
        val gridTop = headerHeight + weekdayBarHeight
        val gridLineBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#DFDAD0")
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(marginH, gridTop, canvasWidth - marginH, gridTop, gridLineBoldPaint)

        // 4. 左侧节次与时间留白列
        val periodNumPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#3E3C37")
            textSize = 22f
            typeface = monoTypeface
            textAlign = Paint.Align.RIGHT
        }
        val periodStartPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#7A766E")
            textSize = 16f
            typeface = monoTypeface
            textAlign = Paint.Align.RIGHT
        }
        val periodEndPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#9E9A92")
            textSize = 14f
            typeface = monoTypeface
            textAlign = Paint.Align.RIGHT
        }
        val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#EDEAE3")
            strokeWidth = 1.2f
            style = Paint.Style.STROKE
        }

        periods.forEachIndexed { pIdx, p ->
            val y = gridTop + pIdx * rowHeight
            val formattedNum = String.format("%02d", pIdx + 1)
            canvas.drawText(formattedNum, marginH + gutterWidth - 18f, y + 42f, periodNumPaint)
            canvas.drawText(Schedule.clockText(p.start), marginH + gutterWidth - 18f, y + 70f, periodStartPaint)
            canvas.drawText(Schedule.clockText(p.end), marginH + gutterWidth - 18f, y + 94f, periodEndPaint)
            // 行底水平网格线
            canvas.drawLine(marginH + gutterWidth, y + rowHeight, canvasWidth - marginH, y + rowHeight, gridLinePaint)
        }

        // 列间垂直分割线
        visibleDays.forEachIndexed { idx, _ ->
            val vx = marginH + gutterWidth + colWidth * idx
            canvas.drawLine(vx, gridTop, vx, gridTop + gridHeight, gridLinePaint)
        }
        canvas.drawLine(canvasWidth - marginH, gridTop, canvasWidth - marginH, gridTop + gridHeight, gridLinePaint)

        // 5. 课程卡片真实色彩与艺术排版渲染
        val courseTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#181715")
            typeface = serifTypeface
        }
        val courseMetaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#4D4A43")
            typeface = serifTypeface
        }
        val courseTimePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#7E7A72")
            typeface = monoTypeface
        }

        visibleDays.forEachIndexed { colIdx, d ->
            val teaching = teachingDays.getOrNull(d - 1)
            val dayCourses = if (teaching != null) {
                courses.filter { it.isOnWeekday(teaching.second) && it.isActiveOnWeek(teaching.first) }
            } else emptyList()

            // 计算重叠车道
            val blocks = dayCourses.mapNotNull { c ->
                val (top, height) = if (c.hasCustomTime()) {
                    Schedule.customCourseLayout(c, periods)
                } else {
                    (c.startPeriod - 1).toFloat() to (c.endPeriod - c.startPeriod + 1).toFloat()
                }
                if (height <= 0f) null else GridBlock(c, top, height)
            }.sortedWith(compareBy({ it.top }, { -it.height }))

            val laneEnds = mutableListOf<Float>()
            val placedBlocks = blocks.map { b ->
                val free = laneEnds.indexOfFirst { it <= b.top + 0.01f }
                val lane = if (free >= 0) free else laneEnds.size.also { laneEnds.add(0f) }
                laneEnds[lane] = b.top + b.height
                b.copy(lane = lane)
            }
            val laneCount = laneEnds.size.coerceAtLeast(1).coerceAtMost(3)
            val laneW = colWidth / laneCount

            placedBlocks.forEach { block ->
                val c = block.course
                val bx = marginH + gutterWidth + colWidth * colIdx + laneW * block.lane.coerceAtMost(laneCount - 1)
                val by = gridTop + block.top * rowHeight
                val bw = laneW
                val bh = block.height * rowHeight

                val blockRect = RectF(bx + 4f, by + 4f, bx + bw - 4f, by + bh - 4f)

                // 核心:使用与主界面 100% 相同算法解析课程实际底色与色标
                val bgArgb = CoursePalette.resolveTint(c, scheme, dark = false).toArgb()
                val markArgb = CoursePalette.resolveMark(c, scheme, dark = false).toArgb()

                // 水彩纸感背景
                val blockBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = bgArgb
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(blockRect, 16f, 16f, blockBgPaint)

                // 雅致微边框:取 markColor 的 25% 透明度，清秀不浑浊
                val borderArgb = android.graphics.Color.argb(
                    65,
                    android.graphics.Color.red(markArgb),
                    android.graphics.Color.green(markArgb),
                    android.graphics.Color.blue(markArgb),
                )
                val blockBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = borderArgb
                    strokeWidth = 1.5f
                    style = Paint.Style.STROKE
                }
                canvas.drawRoundRect(blockRect, 16f, 16f, blockBorderPaint)

                // 左侧和色强调细条 (东方手帐质感)
                val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.argb(
                        220,
                        android.graphics.Color.red(markArgb),
                        android.graphics.Color.green(markArgb),
                        android.graphics.Color.blue(markArgb),
                    )
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(
                    RectF(blockRect.left + 5f, blockRect.top + 8f, blockRect.left + 8.5f, blockRect.bottom - 8f),
                    2f, 2f, barPaint,
                )

                // 卡片内部文字排版 (严格裁切保护，杜绝溢出)
                canvas.save()
                canvas.clipRect(blockRect)

                val contentLeft = blockRect.left + 17f
                val contentRight = blockRect.right - 10f
                val contentWidth = (contentRight - contentLeft).toInt().coerceAtLeast(30)
                var currentY = blockRect.top + 15f

                // 课程名称排版
                val nameSize = if (laneCount > 1) 20f else 24f
                courseTitlePaint.textSize = nameSize
                val maxNameLines = if (bh < 100f) 2 else 3
                val titleLayout = StaticLayout.Builder.obtain(
                    c.name, 0, c.name.length, courseTitlePaint, contentWidth,
                ).setMaxLines(maxNameLines).setLineSpacing(2f, 1f).build()

                canvas.save()
                canvas.translate(contentLeft, currentY)
                titleLayout.draw(canvas)
                canvas.restore()

                currentY += titleLayout.height + 8f

                // 教室地点 (配精致图标)
                if (c.location.isNotBlank() && currentY + 22f <= blockRect.bottom - 8f) {
                    val locSize = if (laneCount > 1) 15f else 17f
                    courseMetaPaint.textSize = locSize
                    val locText = if (c.location.length > 9) c.location.take(8) + "…" else c.location
                    canvas.drawText("📍 $locText", contentLeft, currentY + 16f, courseMetaPaint)
                    currentY += 24f
                }

                // 任课教师
                if (c.teacher.isNotBlank() && currentY + 20f <= blockRect.bottom - 8f) {
                    val teacherSize = if (laneCount > 1) 14f else 16f
                    courseMetaPaint.textSize = teacherSize
                    val teacherText = if (c.teacher.length > 7) c.teacher.take(6) + "…" else c.teacher
                    canvas.drawText("👤 $teacherText", contentLeft, currentY + 16f, courseMetaPaint)
                    currentY += 24f
                }

                // 长时间段节次与时间说明
                if (bh >= 180f && currentY + 20f <= blockRect.bottom - 8f) {
                    courseTimePaint.textSize = 14f
                    val timeDesc = if (c.hasCustomTime()) {
                        Schedule.courseTimeText(c, periods)
                    } else {
                        "${c.startPeriod}-${c.endPeriod}节"
                    }
                    canvas.drawText(timeDesc, contentLeft, currentY + 16f, courseTimePaint)
                }

                canvas.restore()
            }
        }

        // 6. 底部艺术落款与留白结语
        val footerLineY = gridTop + gridHeight + 18f
        canvas.drawLine(marginH, footerLineY, canvasWidth - marginH, footerLineY, dividerLinePaint)

        val footerBrandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#4A4740")
            textSize = 24f
            typeface = serifTypeface
        }
        canvas.drawText("余 白 · 极 简 课 表", marginH, footerLineY + 54f, footerBrandPaint)

        val footerEnPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#99958C")
            textSize = 14f
            typeface = monoTypeface
            letterSpacing = 0.14f
        }
        canvas.drawText("Y O H A K U   C L A S S T A B L E", marginH, footerLineY + 84f, footerEnPaint)

        // 居中艺术铭文
        val footerMottoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#858076")
            textSize = 18f
            typeface = serifTypeface
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("❖   学 习 与 生 活 的 留 白 艺 术   ❖", canvasWidth / 2f, footerLineY + 68f, footerMottoPaint)

        // 右侧导出日期与周次题跋
        val dateNow = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
        val footerDatePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#6E6A62")
            textSize = 17f
            typeface = monoTypeface
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("导 出 于 $dateNow · 第 $week 周", canvasWidth - marginH, footerLineY + 68f, footerDatePaint)

        bitmap
    }

    /**
     * 保存长图到系统相册。
     */
    suspend fun saveToGallery(bitmap: Bitmap, week: Int): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = "余课_第${week}周课表_${System.currentTimeMillis()}"
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/余课")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: error("无法在系统相册中创建图片记录")

            resolver.openOutputStream(uri)?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    error("长图压缩失败")
                }
            } ?: error("无法打开相册写入流")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            uri
        }
    }

    /**
     * 保存长图到临时缓存并返回 content:// URI 供系统分享。
     */
    suspend fun prepareShareImage(bitmap: Bitmap, week: Int): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(exportsDir, "余课_第${week}周课表.png")
            file.outputStream().use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    error("长图输出失败")
                }
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    }
}

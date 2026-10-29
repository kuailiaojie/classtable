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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Yohaku 美学课表长图导出器。
 *
 * 将指定周次的整周课表渲染为高清纸感长图（PNG），保留思源宋体排版与和色微重音，
 * 支持保存至系统相册或通过多渠道分享。
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
     * 生成高清周课表 Bitmap。
     */
    suspend fun generateBitmap(
        week: Int,
        specifiedVisibleDays: List<Int>? = null,
    ): Bitmap = withContext(Dispatchers.Default) {
        val courses = courseRepository.getAll()
        val settings = settingsRepository.settings.first()
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

        // 确定可见星期（5天或7天）
        val visibleDays = specifiedVisibleDays ?: run {
            val weekendHasClass = listOf(6, 7).any { d ->
                val teaching = teachingDays.getOrNull(d - 1)
                teaching != null && courses.any {
                    it.isOnWeekday(teaching.second) && it.isActiveOnWeek(teaching.first)
                }
            }
            if (weekendHasClass) (1..7).toList() else (1..5).toList()
        }

        val canvasWidth = 1200
        val headerHeight = 220f
        val weekdayBarHeight = 90f
        val gutterWidth = 110f
        val rowHeight = 125f
        val footerHeight = 110f
        val gridHeight = periods.size * rowHeight
        val totalHeight = (headerHeight + weekdayBarHeight + gridHeight + footerHeight).toInt()

        val bitmap = Bitmap.createBitmap(canvasWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. 纸底背景 (Yohaku paper #FEFEFB)
        val bgPaint = Paint().apply {
            color = android.graphics.Color.parseColor("#FEFEFB")
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, canvasWidth.toFloat(), totalHeight.toFloat(), bgPaint)

        // 2. 顶部标题与学期信息
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#141312")
            textSize = 50f
            typeface = serifTypeface
        }
        canvas.drawText("余课 · 第 $week 周", 60f, 95f, titlePaint)

        val dateRange = Schedule.weekRangeText(settings.semesterStartDay, week)
        val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#5C5A55")
            textSize = 24f
            typeface = monoTypeface
        }
        val subtitle = buildString {
            if (dateRange.isNotEmpty()) append(dateRange).append(" · ")
            append("学期第 ").append(week).append(" 周")
            if (settings.semesterStartDay <= 0L) append(" · 未设置开学日")
        }
        canvas.drawText(subtitle, 60f, 150f, subtitlePaint)

        // 顶部分隔细线
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#E7E5DF")
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(50f, headerHeight - 10f, canvasWidth - 50f, headerHeight - 10f, linePaint)

        // 3. 表头星期栏
        val colWidth = (canvasWidth - 100f - gutterWidth) / visibleDays.size
        val weekdayNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#141312")
            textSize = 28f
            typeface = serifTypeface
            textAlign = Paint.Align.CENTER
        }
        val dayNumPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#787670")
            textSize = 20f
            typeface = monoTypeface
            textAlign = Paint.Align.CENTER
        }

        val weekDates = Schedule.weekDates(settings.semesterStartDay, week)
        visibleDays.forEachIndexed { idx, d ->
            val cx = 50f + gutterWidth + colWidth * idx + colWidth / 2f
            val weekdayText = Dates.weekdayLabel(d)
            canvas.drawText(weekdayText, cx, headerHeight + 36f, weekdayNamePaint)
            val dateStr = weekDates.getOrNull(d - 1)?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: ""
            if (dateStr.isNotEmpty()) {
                canvas.drawText(dateStr, cx, headerHeight + 68f, dayNumPaint)
            }
        }

        // 表头下分隔线
        val gridTop = headerHeight + weekdayBarHeight
        canvas.drawLine(50f, gridTop, canvasWidth - 50f, gridTop, linePaint)

        // 4. 左侧节次与时间留白列
        val periodNumPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#403F3A")
            textSize = 24f
            typeface = monoTypeface
            textAlign = Paint.Align.RIGHT
        }
        val periodTimePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#787670")
            textSize = 18f
            typeface = monoTypeface
            textAlign = Paint.Align.RIGHT
        }
        val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#F0EFEB")
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }

        periods.forEachIndexed { pIdx, p ->
            val y = gridTop + pIdx * rowHeight
            // 节号与时间
            canvas.drawText("${pIdx + 1}", 50f + gutterWidth - 16f, y + 36f, periodNumPaint)
            canvas.drawText(Schedule.clockText(p.start), 50f + gutterWidth - 16f, y + 62f, periodTimePaint)
            // 行底水平线
            canvas.drawLine(50f + gutterWidth, y + rowHeight, canvasWidth - 50f, y + rowHeight, gridLinePaint)
        }

        // 列间垂直分割线
        visibleDays.forEachIndexed { idx, _ ->
            val vx = 50f + gutterWidth + colWidth * idx
            canvas.drawLine(vx, gridTop, vx, gridTop + gridHeight, gridLinePaint)
        }
        canvas.drawLine(canvasWidth - 50f, gridTop, canvasWidth - 50f, gridTop + gridHeight, gridLinePaint)

        // 5. 课程块排布与渲染
        val courseTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#141312")
            textSize = 24f
            typeface = serifTypeface
        }
        val courseMetaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#5C5A55")
            textSize = 18f
            typeface = monoTypeface
        }

        visibleDays.forEachIndexed { colIdx, d ->
            val teaching = teachingDays.getOrNull(d - 1)
            val dayCourses = if (teaching != null) {
                courses.filter { it.isOnWeekday(teaching.second) && it.isActiveOnWeek(teaching.first) }
            } else emptyList()

            // 计算车道
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
                val bx = 50f + gutterWidth + colWidth * colIdx + laneW * block.lane.coerceAtMost(laneCount - 1)
                val by = gridTop + block.top * rowHeight
                val bw = laneW
                val bh = block.height * rowHeight

                val blockRect = RectF(bx + 3f, by + 3f, bx + bw - 3f, by + bh - 3f)

                // 纯净和色背景计算
                val hex = c.colorHex.ifBlank {
                    val hue = c.colorHue ?: CoursePalette.PRESETS[Math.abs(c.name.hashCode()) % CoursePalette.PRESETS.size].first.hashCode()
                    CoursePalette.PRESETS[Math.abs(hue) % CoursePalette.PRESETS.size].second
                }
                val rawColor = runCatching { android.graphics.Color.parseColor(hex) }.getOrDefault(android.graphics.Color.parseColor("#C56473"))
                val tintBgColor = android.graphics.Color.argb(
                    38, // ~15% 不透明度
                    android.graphics.Color.red(rawColor),
                    android.graphics.Color.green(rawColor),
                    android.graphics.Color.blue(rawColor),
                )
                val blockBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = tintBgColor
                    style = Paint.Style.FILL
                }
                val blockBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.argb(
                        90,
                        android.graphics.Color.red(rawColor),
                        android.graphics.Color.green(rawColor),
                        android.graphics.Color.blue(rawColor),
                    )
                    strokeWidth = 2f
                    style = Paint.Style.STROKE
                }

                canvas.drawRoundRect(blockRect, 14f, 14f, blockBgPaint)
                canvas.drawRoundRect(blockRect, 14f, 14f, blockBorderPaint)

                // 绘制左边缘和色强调小条
                val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.argb(
                        200,
                        android.graphics.Color.red(rawColor),
                        android.graphics.Color.green(rawColor),
                        android.graphics.Color.blue(rawColor),
                    )
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(
                    RectF(blockRect.left, blockRect.top + 6f, blockRect.left + 5f, blockRect.bottom - 6f),
                    3f, 3f, barPaint,
                )

                // 文字排版
                var textY = blockRect.top + 28f
                // 课程名称
                val nameWidth = (blockRect.width() - 20f).toInt().coerceAtLeast(30)
                val titleLayout = StaticLayout.Builder.obtain(
                    c.name, 0, c.name.length, courseTitlePaint, nameWidth,
                ).setMaxLines(3).build()

                canvas.save()
                canvas.translate(blockRect.left + 12f, textY - 20f)
                titleLayout.draw(canvas)
                canvas.restore()

                textY += titleLayout.height + 6f

                // 地点与教室
                if (c.location.isNotBlank() && textY + 20f <= blockRect.bottom) {
                    val locText = if (c.location.length > 8) c.location.take(7) + "…" else c.location
                    canvas.drawText(locText, blockRect.left + 12f, textY, courseMetaPaint)
                    textY += 22f
                }

                // 教师
                if (c.teacher.isNotBlank() && textY + 18f <= blockRect.bottom) {
                    val teacherText = if (c.teacher.length > 8) c.teacher.take(7) + "…" else c.teacher
                    canvas.drawText(teacherText, blockRect.left + 12f, textY, courseMetaPaint)
                }
            }
        }

        // 6. 底部品牌与留白印记
        val footerLineY = gridTop + gridHeight + 20f
        canvas.drawLine(50f, footerLineY, canvasWidth - 50f, footerLineY, linePaint)

        val footerBrandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#A8A69F")
            textSize = 22f
            typeface = serifTypeface
            textAlign = Paint.Align.CENTER
        }
        val footerY = footerLineY + 50f
        canvas.drawText("余 白 · 极 简 课 表   |   Y O H A K U", canvasWidth / 2f, footerY, footerBrandPaint)

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

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
import android.text.TextUtils
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
 * 专为现代竖屏智能手机（9:19.5 屏幕与壁纸比例）定制的 1800×3900 超清艺术画报级课表长图渲染引擎。
 * 严格对齐课程在界面中的 OKLCh 淡彩与和色标，保留思源宋体排版、朱砂落款印与留白美学，
 * 采用自适应版式排布，确保每门课程的教室地点与授课教师稳定一致地呈现，杜绝因节次高度不足被截断丢弃。
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
     * 生成高清艺术周课表 Bitmap（1800×3900，标准竖屏手机 9:19.5 黄金比例）。
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
        val sansTypeface = Typeface.create("sans-serif", Typeface.NORMAL)

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

        // 现代智能手机竖屏黄金规格：1800 × 3900 (9 : 19.5 全面屏壁纸比例)
        val canvasWidth = 1800
        val totalHeight = 3900
        val marginH = 68f
        val headerHeight = 360f
        val weekdayBarHeight = 164f
        val gutterWidth = 144f
        val footerHeight = 220f

        val gridTop = headerHeight + weekdayBarHeight
        val gridBottom = totalHeight - footerHeight
        val gridHeight = gridBottom - gridTop
        val periodCount = periods.size.coerceAtLeast(1)
        val rowHeight = gridHeight / periodCount

        val bitmap = Bitmap.createBitmap(canvasWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. 暖纸底背景 (Yohaku paper #FAF8F5)
        val bgPaint = Paint().apply {
            color = android.graphics.Color.parseColor("#FAF8F5")
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, canvasWidth.toFloat(), totalHeight.toFloat(), bgPaint)

        // 艺术典雅双重外框 (Museum-grade double frame)
        val outerFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#E4DFD2")
            strokeWidth = 2.5f
            style = Paint.Style.STROKE
        }
        canvas.drawRect(28f, 28f, canvasWidth - 28f, totalHeight - 28f, outerFramePaint)

        val innerHairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#EFEBE0")
            strokeWidth = 1.2f
            style = Paint.Style.STROKE
        }
        canvas.drawRect(40f, 40f, canvasWidth - 40f, totalHeight - 40f, innerHairlinePaint)

        // 2. 顶部艺术排版与朱砂印章
        // 顶栏眉线
        val eyebrowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = android.graphics.Color.parseColor("#7E7A71")
            textSize = 24f
            typeface = sansTypeface
            letterSpacing = 0.16f
        }
        canvas.drawText("—  Y O H A K U   ·   学 期 课 程 历  —", marginH, 106f, eyebrowPaint)

        // 主标题
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = android.graphics.Color.parseColor("#141311")
            textSize = 72f
            typeface = serifTypeface
        }
        canvas.drawText("第 $week 周 课程表", marginH, 192f, titlePaint)

        // 朱砂印章 (落款印「余课」)
        val stampSize = 84f
        val stampRight = canvasWidth - marginH
        val stampTop = 110f
        val stampRect = RectF(stampRight - stampSize, stampTop, stampRight, stampTop + stampSize)
        val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#C56473") // 传统梅红/朱砂
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(stampRect, 14f, 14f, stampPaint)

        val stampInnerRect = RectF(stampRect.left + 4f, stampRect.top + 4f, stampRect.right - 4f, stampRect.bottom - 4f)
        val stampBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(180, 255, 255, 255)
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        canvas.drawRoundRect(stampInnerRect, 10f, 10f, stampBorderPaint)

        val stampTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 32f
            typeface = serifTypeface
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("余", stampRect.centerX(), stampRect.top + 34f, stampTextPaint)
        canvas.drawText("课", stampRect.centerX(), stampRect.top + 70f, stampTextPaint)

        // 副标题与元信息行
        val dateRange = Schedule.weekRangeText(settings.semesterStartDay, week)
        val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = android.graphics.Color.parseColor("#4C483F")
            textSize = 28f
            typeface = monoTypeface
        }
        val subtitleText = buildString {
            if (dateRange.isNotEmpty()) append(dateRange).append("  ·  ")
            append("学期第 ").append(week).append(" 周")
            if (settings.semesterStartDay <= 0L) append(" (未设置开学日)")
        }
        canvas.drawText(subtitleText, marginH, 268f, subtitlePaint)

        // 本周课程统计胶囊
        val activeCourseCount = courses.count { it.isActiveOnWeek(week) }
        val countBadgeText = "本周共 $activeCourseCount 节课程"
        val countBadgeWidth = subtitlePaint.measureText(countBadgeText) + 48f
        val countBadgeRect = RectF(
            stampRight - countBadgeWidth,
            236f,
            stampRight,
            292f,
        )
        val countBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#EBE7DE")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(countBadgeRect, 28f, 28f, countBadgeBgPaint)
        val countBadgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = android.graphics.Color.parseColor("#4A463E")
            textSize = 24f
            typeface = serifTypeface
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(countBadgeText, countBadgeRect.centerX(), countBadgeRect.centerY() + 8f, countBadgeTextPaint)

        // 顶部分隔细线
        val dividerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#DDD8CE")
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(marginH, headerHeight - 14f, canvasWidth - marginH, headerHeight - 14f, dividerLinePaint)

        // 3. 表头星期栏排版
        val colWidth = (canvasWidth - 2 * marginH - gutterWidth) / visibleDays.size
        val weekdayNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = android.graphics.Color.parseColor("#151412")
            textSize = 38f
            typeface = serifTypeface
            textAlign = Paint.Align.CENTER
        }
        val weekdayEnPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = android.graphics.Color.parseColor("#7A766E")
            textSize = 23f
            typeface = monoTypeface
            textAlign = Paint.Align.CENTER
        }
        val dayNumPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = android.graphics.Color.parseColor("#4A4740")
            textSize = 28f
            typeface = monoTypeface
            textAlign = Paint.Align.CENTER
        }

        val weekDates = Schedule.weekDates(settings.semesterStartDay, week)
        val todayWeekday = Schedule.todayWeekday()
        val currentWeek = Schedule.currentWeek(settings.semesterStartDay, settings.semesterWeekCount)
        val isViewingCurrentWeek = week == currentWeek

        visibleDays.forEachIndexed { idx, d ->
            val colLeft = marginH + gutterWidth + colWidth * idx
            val cx = colLeft + colWidth / 2f
            val isTodayCol = isViewingCurrentWeek && d == todayWeekday

            // 若为今天，绘制柔和高亮底衬
            if (isTodayCol) {
                val todayHeaderRect = RectF(
                    colLeft + 3f,
                    headerHeight + 4f,
                    colLeft + colWidth - 3f,
                    headerHeight + weekdayBarHeight - 12f,
                )
                val todayHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.parseColor("#ECE6DA")
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(todayHeaderRect, 18f, 18f, todayHeaderPaint)
            }

            // 星期文字
            val weekdayText = Dates.weekdayLabel(d)
            canvas.drawText(weekdayText, cx, headerHeight + 52f, weekdayNamePaint)

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
            canvas.drawText(enWeekday, cx, headerHeight + 86f, weekdayEnPaint)

            // 日期文本
            val dateStr = weekDates.getOrNull(d - 1)?.let {
                "${it.monthValue.toString().padStart(2, '0')}/${it.dayOfMonth.toString().padStart(2, '0')}"
            } ?: ""
            if (dateStr.isNotEmpty()) {
                canvas.drawText(dateStr, cx, headerHeight + 128f, dayNumPaint)
            }
        }

        // 表头下分隔线
        val gridLineBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#D5CFC4")
            strokeWidth = 2.5f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(marginH, gridTop, canvasWidth - marginH, gridTop, gridLineBoldPaint)

        // 4. 左侧节次与时间留白列
        val periodNumPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = android.graphics.Color.parseColor("#262420")
            textSize = 38f
            typeface = monoTypeface
            isFakeBoldText = true
            textAlign = Paint.Align.RIGHT
        }
        val periodStartPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = android.graphics.Color.parseColor("#6A655C")
            textSize = 24f
            typeface = monoTypeface
            textAlign = Paint.Align.RIGHT
        }
        val periodEndPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = android.graphics.Color.parseColor("#8E8980")
            textSize = 21f
            typeface = monoTypeface
            textAlign = Paint.Align.RIGHT
        }
        val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#ECE8DF")
            strokeWidth = 1.2f
            style = Paint.Style.STROKE
        }

        periods.forEachIndexed { pIdx, p ->
            val y = gridTop + pIdx * rowHeight
            val formattedNum = String.format("%02d", pIdx + 1)
            canvas.drawText(formattedNum, marginH + gutterWidth - 22f, y + rowHeight * 0.36f, periodNumPaint)
            canvas.drawText(Schedule.clockText(p.start), marginH + gutterWidth - 22f, y + rowHeight * 0.63f, periodStartPaint)
            canvas.drawText(Schedule.clockText(p.end), marginH + gutterWidth - 22f, y + rowHeight * 0.86f, periodEndPaint)
            // 行底水平网格线
            canvas.drawLine(marginH + gutterWidth, y + rowHeight, canvasWidth - marginH, y + rowHeight, gridLinePaint)
        }

        // 列间垂直分割线
        visibleDays.forEachIndexed { idx, _ ->
            val vx = marginH + gutterWidth + colWidth * idx
            canvas.drawLine(vx, gridTop, vx, gridBottom, gridLinePaint)
        }
        canvas.drawLine(canvasWidth - marginH, gridTop, canvasWidth - marginH, gridBottom, gridLinePaint)

        // 5. 课程卡片真实色彩与艺术排版渲染
        val courseTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = android.graphics.Color.parseColor("#1A1816")
            typeface = serifTypeface
        }
        val courseMetaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = android.graphics.Color.parseColor("#454139")
            typeface = sansTypeface
        }
        val courseTeacherPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = android.graphics.Color.parseColor("#5C574E")
            typeface = sansTypeface
        }
        val courseTimePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = android.graphics.Color.parseColor("#757067")
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

                val blockRect = RectF(bx + 4.5f, by + 5f, bx + bw - 4.5f, by + bh - 5f)

                // 核心:使用与主界面 100% 相同算法解析课程实际底色与色标
                val bgArgb = CoursePalette.resolveTint(c, scheme, dark = false).toArgb()
                val markArgb = CoursePalette.resolveMark(c, scheme, dark = false).toArgb()

                // 纸感立体微阴影 (Paper Elevation Soft Shadow)
                val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.argb(22, 25, 20, 15)
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(
                    RectF(blockRect.left + 2f, blockRect.top + 6f, blockRect.right - 2f, blockRect.bottom + 8f),
                    24f, 24f, shadowPaint,
                )

                // 水彩纸感背景
                val blockBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = bgArgb
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(blockRect, 24f, 24f, blockBgPaint)

                // 雅致微边框:取 markColor 的 35% 透明度，清秀不浑浊
                val borderArgb = android.graphics.Color.argb(
                    85,
                    android.graphics.Color.red(markArgb),
                    android.graphics.Color.green(markArgb),
                    android.graphics.Color.blue(markArgb),
                )
                val blockBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = borderArgb
                    strokeWidth = 2f
                    style = Paint.Style.STROKE
                }
                canvas.drawRoundRect(blockRect, 24f, 24f, blockBorderPaint)

                // 左侧和色强调细条 (东方手帐和纸贴条质感)
                val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.argb(
                        230,
                        android.graphics.Color.red(markArgb),
                        android.graphics.Color.green(markArgb),
                        android.graphics.Color.blue(markArgb),
                    )
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(
                    RectF(blockRect.left + 6.5f, blockRect.top + 10f, blockRect.left + 12.5f, blockRect.bottom - 10f),
                    3f, 3f, barPaint,
                )

                // 卡片内部文字排版 (自适应留白与精致字阶排版)
                canvas.save()
                canvas.clipRect(blockRect)

                val contentLeft = blockRect.left + if (laneCount > 1) 14f else 20f
                val contentRight = blockRect.right - if (laneCount > 1) 8f else 12f
                val contentWidth = (contentRight - contentLeft).toInt().coerceAtLeast(30)
                var currentY = blockRect.top + if (bh < 260f) 14f else 18f

                val hasLocation = c.location.isNotBlank()
                val hasTeacher = c.teacher.isNotBlank()

                // 1. 课程名称排版 (与列宽相得益彰的黄金字号，杜绝字号过大撑爆与单字生硬折行)
                val nameSize = when {
                    laneCount >= 3 -> 16f
                    laneCount == 2 -> 20f
                    contentWidth >= 220 -> if (bh >= 380f) 34f else 30f // 5天视图 (列宽较宽)
                    bh < 260f -> 27f                                      // 1节课单节紧凑
                    bh < 380f -> 30f                                      // 1.5-2节适中
                    else -> 32f                                           // 大课 (>=2节)
                }
                courseTitlePaint.textSize = nameSize
                val titleMaxLines = when {
                    bh < 220f -> 1
                    bh < 360f -> 2
                    else -> 3
                }

                val titleLayout = StaticLayout.Builder.obtain(
                    c.name, 0, c.name.length, courseTitlePaint, contentWidth,
                ).setMaxLines(titleMaxLines)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setLineSpacing(5f, 1.15f)
                    .build()

                canvas.save()
                canvas.translate(contentLeft, currentY)
                titleLayout.draw(canvas)
                canvas.restore()

                currentY += titleLayout.height + if (bh < 260f) 6f else 10f

                // 2. 地点、教师与时间排版 (使用 Sans-serif 清秀字体 + TextUtils.ellipsize 精确防溢出)
                val remH = blockRect.bottom - 10f - currentY

                val locSize = if (laneCount > 1) 17f else 21f
                val teacherSize = if (laneCount > 1) 16f else 19f
                val timeSize = if (laneCount > 1) 15f else 18f

                courseMetaPaint.textSize = locSize
                courseTeacherPaint.textSize = teacherSize
                courseTimePaint.textSize = timeSize

                if (bh >= 360f) {
                    // 宽裕大课（>=2节）：地点与教师分行独立呈现
                    if (hasLocation && remH >= 24f) {
                        val locText = TextUtils.ellipsize(c.location, courseMetaPaint, contentWidth.toFloat(), TextUtils.TruncateAt.END).toString()
                        val fm = courseMetaPaint.fontMetrics
                        canvas.drawText(locText, contentLeft, currentY - fm.ascent, courseMetaPaint)
                        currentY += (fm.descent - fm.ascent) + 6f
                    }
                    if (hasTeacher && blockRect.bottom - 10f - currentY >= 22f) {
                        val teacherText = TextUtils.ellipsize(c.teacher, courseTeacherPaint, contentWidth.toFloat(), TextUtils.TruncateAt.END).toString()
                        val fm = courseTeacherPaint.fontMetrics
                        canvas.drawText(teacherText, contentLeft, currentY - fm.ascent, courseTeacherPaint)
                        currentY += (fm.descent - fm.ascent) + 6f
                    }
                    if (bh >= 480f && blockRect.bottom - 10f - currentY >= 20f) {
                        val timeDesc = if (c.hasCustomTime()) {
                            Schedule.courseTimeText(c, periods)
                        } else {
                            "${c.startPeriod}-${c.endPeriod}节"
                        }
                        val timeText = TextUtils.ellipsize(timeDesc, courseTimePaint, contentWidth.toFloat(), TextUtils.TruncateAt.END).toString()
                        val fm = courseTimePaint.fontMetrics
                        canvas.drawText(timeText, contentLeft, currentY - fm.ascent, courseTimePaint)
                    }
                } else {
                    // 紧凑或单节课程：优先在一至两行内妥善排布地点与教师
                    val metaLineHeight = courseMetaPaint.fontMetrics.let { it.descent - it.ascent }
                    val canFitTwoLines = remH >= (metaLineHeight * 2 + 6f)

                    if (canFitTwoLines && hasLocation && hasTeacher) {
                        val locText = TextUtils.ellipsize(c.location, courseMetaPaint, contentWidth.toFloat(), TextUtils.TruncateAt.END).toString()
                        val fmLoc = courseMetaPaint.fontMetrics
                        canvas.drawText(locText, contentLeft, currentY - fmLoc.ascent, courseMetaPaint)
                        currentY += (fmLoc.descent - fmLoc.ascent) + 4f

                        val teacherText = TextUtils.ellipsize(c.teacher, courseTeacherPaint, contentWidth.toFloat(), TextUtils.TruncateAt.END).toString()
                        val fmTeacher = courseTeacherPaint.fontMetrics
                        canvas.drawText(teacherText, contentLeft, currentY - fmTeacher.ascent, courseTeacherPaint)
                    } else if (remH >= metaLineHeight) {
                        val combined = when {
                            hasLocation && hasTeacher -> "${c.location} · ${c.teacher}"
                            hasLocation -> c.location
                            hasTeacher -> c.teacher
                            else -> null
                        }
                        if (combined != null) {
                            val text = TextUtils.ellipsize(combined, courseMetaPaint, contentWidth.toFloat(), TextUtils.TruncateAt.END).toString()
                            val fm = courseMetaPaint.fontMetrics
                            canvas.drawText(text, contentLeft, currentY - fm.ascent, courseMetaPaint)
                        }
                    }
                }

                canvas.restore()
            }
        }

        // 6. 底部艺术落款与留白结语
        val footerLineY = gridBottom
        canvas.drawLine(marginH, footerLineY, canvasWidth - marginH, footerLineY, dividerLinePaint)

        val footerBrandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = android.graphics.Color.parseColor("#322F2A")
            textSize = 32f
            typeface = serifTypeface
            isFakeBoldText = true
        }
        canvas.drawText("余 白 · 极 简 课 表", marginH, footerLineY + 68f, footerBrandPaint)

        val footerEnPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = android.graphics.Color.parseColor("#8E8A80")
            textSize = 18f
            typeface = monoTypeface
            letterSpacing = 0.16f
        }
        canvas.drawText("Y O H A K U   C L A S S T A B L E", marginH, footerLineY + 106f, footerEnPaint)

        // 居中艺术铭文
        val footerMottoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = android.graphics.Color.parseColor("#747065")
            textSize = 26f
            typeface = serifTypeface
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("·   学 习 与 生 活 的 留 白 艺 术   ·", canvasWidth / 2f, footerLineY + 84f, footerMottoPaint)

        // 右侧导出日期与周次题跋
        val dateNow = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
        val footerDatePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = android.graphics.Color.parseColor("#5A564E")
            textSize = 24f
            typeface = monoTypeface
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("导 出 于 $dateNow · 第 $week 周", canvasWidth - marginH, footerLineY + 84f, footerDatePaint)

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

package com.kxin.classtable.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.Course

/** 小组件外壳:圆角 + 底色 + 内边距。三款共用,风格才不会各不相同。 */
@Composable
internal fun WidgetFrame(
    skin: WidgetSkin,
    prefs: WidgetPrefs,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(if (prefs.compact) 10.dp else 14.dp)
            .background(skin.background)
            .padding(if (prefs.compact) 8.dp else 12.dp),
    ) {
        content()
    }
}

/** 小标题(「今日」「明天」「下节课」)。 */
@Composable
internal fun WidgetTitle(text: String, skin: WidgetSkin) {
    Text(
        text = text,
        style = TextStyle(fontSize = 11.sp, color = ColorProvider(skin.sub)),
        maxLines = 1,
    )
}

/**
 * 一门课在小组件里的一行:时刻 / 节次 · 课程名 · 地点教师。
 * 显示哪些由选项决定,不显示的部分不占宽度。
 */
@Composable
internal fun CourseLine(
    course: Course,
    periods: List<Schedule.Period>,
    prefs: WidgetPrefs,
    skin: WidgetSkin,
    highlight: Boolean = false,
) {
    // 「紧凑」收紧的是行距,不是字号 —— 字号压小只会让字看着小,并不会让内容更密
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(top = if (prefs.compact) 2.dp else 4.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        if (highlight) {
            Box(GlanceModifier.width(3.dp).height(18.dp).background(skin.accent)) {}
            Spacer(GlanceModifier.width(6.dp))
        }
        if (prefs.showTime) {
            Text(
                text = Schedule.courseTimeText(course, periods),
                style = TextStyle(fontSize = 11.sp, color = ColorProvider(skin.sub)),
                modifier = GlanceModifier.width(74.dp),
                maxLines = 1,
            )
        }
        if (prefs.showPeriod && !course.isCustomScheduled()) {
            Text(
                text = "${course.startPeriod}-${course.endPeriod} 节",
                style = TextStyle(fontSize = 10.sp, color = ColorProvider(skin.sub)),
                modifier = GlanceModifier.width(46.dp),
                maxLines = 1,
            )
        }
        // Glance 没有 weight 修饰符:前面几列给固定宽度,最后一列 fillMaxWidth 吃掉剩余空间
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                text = course.name,
                style = TextStyle(
                    fontSize = 12.sp,
                    color = ColorProvider(skin.ink),
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            val meta = buildList {
                if (prefs.showLocation && course.location.isNotBlank()) add(course.location)
                if (prefs.showTeacher && course.teacher.isNotBlank()) add(course.teacher)
            }.joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = TextStyle(fontSize = 10.sp, color = ColorProvider(skin.sub)),
                    maxLines = 1,
                )
            }
        }
    }
}

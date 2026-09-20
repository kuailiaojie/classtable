package com.kxin.classtable.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.Course

/**
 * 1×1「下节课」:今天最早一节还没上完的课;调休停课时说「今天停课」。
 * 外观(底色/圆角/显示哪些信息)由小组件选项决定。数据变化时由仓库调用 updateAll 刷新。
 */
class NextClassWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = WidgetPrefsStore.read(context)
        val skin = skinOf(context, prefs)
        val periods = WidgetData.periods(context)
        val rest = WidgetData.todayIsRest(context)
        val course = if (rest) null else WidgetData.nextCourse(context)
        provideContent { NextClassContent(course, periods, prefs, skin, rest) }
    }
}

class NextClassWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextClassWidget()
}

@Composable
fun NextClassContent(
    course: Course?,
    periods: List<Schedule.Period>,
    prefs: WidgetPrefs,
    skin: WidgetSkin,
    rest: Boolean,
) {
    WidgetFrame(skin, prefs) {
        Row(modifier = GlanceModifier.fillMaxHeight()) {
            // accent 只留一条边:它是「此刻」的标记,不是装饰
            if (course != null) {
                Box(
                    modifier = GlanceModifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(skin.accent),
                ) {}
            }
            Column(modifier = GlanceModifier.padding(start = if (course != null) 8.dp else 0.dp)) {
                WidgetTitle(if (rest) "今天" else "下节课", skin)
                when {
                    rest -> Text(
                        text = "今天停课(调休)",
                        style = TextStyle(fontSize = 12.sp, color = ColorProvider(skin.sub)),
                        maxLines = 2,
                    )
                    course == null -> Text(
                        text = "暂无课程",
                        style = TextStyle(fontSize = 12.sp, color = ColorProvider(skin.sub)),
                        maxLines = 1,
                    )
                    else -> {
                        Text(
                            text = course.name,
                            style = TextStyle(
                                fontSize = if (prefs.compact) 13.sp else 14.sp,
                                color = ColorProvider(skin.ink),
                                fontWeight = FontWeight.Medium,
                            ),
                            maxLines = 1,
                        )
                        val line = buildList {
                            if (prefs.showTime) add(Schedule.courseTimeText(course, periods))
                            if (prefs.showLocation && course.location.isNotBlank()) add(course.location)
                            if (prefs.showTeacher && course.teacher.isNotBlank()) add(course.teacher)
                        }.joinToString(" · ")
                        if (line.isNotEmpty()) {
                            Text(
                                text = line,
                                style = TextStyle(fontSize = 11.sp, color = ColorProvider(skin.sub)),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

package com.kxin.classtable.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.Course

/**
 * 1×1「下节课」:accent 仅 4px 左边条。
 * 数据变化时由 CourseRepository 调用 updateAll 刷新。
 */
class NextClassWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val course = WidgetData.nextCourse(context)
        val periods = WidgetData.periods(context)
        provideContent { NextClassContent(course, periods) }
    }
}

class NextClassWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextClassWidget()
}

@Composable
fun NextClassContent(course: Course?, periods: List<Schedule.Period>) {
    val paper = ColorProvider(Color(0xFFFEFEFB))
    val ink = ColorProvider(Color(0xFF141312))
    val sub = ColorProvider(Color(0xFF5C5A55))
    val accent = ColorProvider(Color(0xFFC56473))

    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(8.dp)
            .background(paper)
            .padding(8.dp),
    ) {
        Box(
            modifier = GlanceModifier
                .width(4.dp)
                .fillMaxHeight()
                .background(accent),
        ) {}
        Column(modifier = GlanceModifier.padding(start = 8.dp)) {
            Text(
                text = "下节课",
                style = TextStyle(fontSize = 11.sp, color = sub),
            )
            if (course != null) {
                Text(
                    text = course.name,
                    style = TextStyle(fontSize = 14.sp, color = ink, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
                Text(
                    text = Schedule.courseTimeText(course, periods) +
                        if (course.location.isNotEmpty()) " ${course.location}" else "",
                    style = TextStyle(fontSize = 11.sp, color = sub),
                    maxLines = 1,
                )
            } else {
                Text(
                    text = "暂无课程",
                    style = TextStyle(fontSize = 12.sp, color = sub),
                    maxLines = 1,
                )
            }
        }
    }
}

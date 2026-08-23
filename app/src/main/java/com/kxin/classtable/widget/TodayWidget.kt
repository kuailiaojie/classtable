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
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.Course

/** 4×2「今日课表」:时间 + 课程名;正在上的课带 accent 左边条。 */
class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val courses = WidgetData.todayCourses(context)
        val periods = WidgetData.periods(context)
        val currentId = WidgetData.currentCourseToday(context)?.id
        provideContent { TodayContent(courses, periods, currentId) }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

@Composable
fun TodayContent(courses: List<Course>, periods: List<Schedule.Period>, currentId: String?) {
    val paper = ColorProvider(Color(0xFFFEFEFB))
    val ink = ColorProvider(Color(0xFF141312))
    val sub = ColorProvider(Color(0xFF5C5A55))
    val accent = ColorProvider(Color(0xFFC56473))

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(8.dp)
            .background(paper)
            .padding(12.dp),
    ) {
        Text(text = "今日", style = TextStyle(fontSize = 11.sp, color = sub))
        if (courses.isEmpty()) {
            Text(text = "今日无课", style = TextStyle(fontSize = 12.sp, color = sub))
        } else {
            courses.take(4).forEach { course ->
                Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp)) {
                    if (course.id == currentId) {
                        Box(
                            modifier = GlanceModifier
                                .width(4.dp)
                                .fillMaxHeight()
                                .background(accent),
                        ) {}
                    }
                    Text(
                        text = Schedule.courseTimeText(course, periods),
                        style = TextStyle(fontSize = 11.sp, color = sub),
                        modifier = GlanceModifier.width(72.dp),
                    )
                    Text(
                        text = course.name,
                        style = TextStyle(fontSize = 12.sp, color = ink),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

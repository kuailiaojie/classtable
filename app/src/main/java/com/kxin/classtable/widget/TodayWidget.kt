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
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.height
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.Course

/**
 * 4×2「今日课表」:表头写「周几 · 第几周」,列表最多显示选项里设定的门数,
 * 正在上的课带 accent 左边条;调休停课时直接说明原因。
 */
class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = WidgetPrefsStore.read(context)
        val skin = skinOf(context, prefs)
        val periods = WidgetData.periods(context)
        val courses = WidgetData.todayCourses(context)
        val currentId = WidgetData.currentCourseToday(context)?.id
        val header = WidgetData.header(context, java.time.LocalDate.now())
        provideContent { TodayContent(courses, periods, prefs, skin, currentId, header) }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

@Composable
fun TodayContent(
    courses: List<Course>,
    periods: List<Schedule.Period>,
    prefs: WidgetPrefs,
    skin: WidgetSkin,
    currentId: String?,
    header: String,
) {
    WidgetFrame(skin, prefs) {
        Column {
            WidgetTitle(header, skin)
            when {
                courses.isEmpty() -> Text(
                    text = if (header.contains("停课")) "今天停课(调休)" else "今天没有课",
                    style = TextStyle(fontSize = 12.sp, color = ColorProvider(skin.sub)),
                    maxLines = 2,
                )
                else -> courses.take(prefs.maxRows).forEach { course ->
                    CourseLine(
                        course = course,
                        periods = periods,
                        prefs = prefs,
                        skin = skin,
                        highlight = course.id == currentId,
                    )
                }
            }
            if (courses.size > prefs.maxRows) {
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = "还有 ${courses.size - prefs.maxRows} 门",
                    style = TextStyle(fontSize = 10.sp, color = ColorProvider(skin.sub)),
                    maxLines = 1,
                )
            }
        }
    }
}

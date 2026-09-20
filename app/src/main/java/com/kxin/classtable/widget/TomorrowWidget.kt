package com.kxin.classtable.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.Course
import java.time.LocalDate

/**
 * 2×2「明日课程」:今天看明天的课,不用等到第二天。
 *
 * 与「今日课表」同源同款,只是数据取明天 —— 调休也照样生效(明天补课就显示补的那天的课)。
 */
class TomorrowWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = WidgetPrefsStore.read(context)
        val skin = skinOf(context, prefs)
        val periods = WidgetData.periods(context)
        val courses = WidgetData.tomorrowCourses(context)
        val header = WidgetData.header(context, LocalDate.now().plusDays(1))
        provideContent { TomorrowContent(courses, periods, prefs, skin, header) }
    }
}

class TomorrowWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TomorrowWidget()
}

@Composable
fun TomorrowContent(
    courses: List<Course>,
    periods: List<Schedule.Period>,
    prefs: WidgetPrefs,
    skin: WidgetSkin,
    header: String,
) {
    WidgetFrame(skin, prefs) {
        Column {
            WidgetTitle("明天 · $header", skin)
            if (courses.isEmpty()) {
                Text(
                    text = if (header.contains("停课")) "明天停课(调休)" else "明天没有课",
                    style = TextStyle(fontSize = 12.sp, color = ColorProvider(skin.sub)),
                    maxLines = 2,
                )
            } else {
                courses.take(prefs.maxRows).forEach { course ->
                    CourseLine(
                        course = course,
                        periods = periods,
                        prefs = prefs,
                        skin = skin,
                    )
                }
            }
        }
    }
}

package com.kxin.classtable.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 课程提醒闹钟接收器:触发时构建并展示通知。
 * 内容在触发时刻动态计算(剩余分钟/开始时间/地点/教师),不是静态文案。
 */
class CourseReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val courseId = intent.getStringExtra(NotificationScheduler.EXTRA_COURSE_ID) ?: return
        val name = intent.getStringExtra(NotificationScheduler.EXTRA_COURSE_NAME) ?: "课程"
        val startMinute = intent.getIntExtra(NotificationScheduler.EXTRA_START_MINUTE, -1)
        val location = intent.getStringExtra(NotificationScheduler.EXTRA_LOCATION).orEmpty()
        val teacher = intent.getStringExtra(NotificationScheduler.EXTRA_TEACHER).orEmpty()
        val lead = intent.getIntExtra(NotificationScheduler.EXTRA_LEAD, 10)

        Notifier.showCourseReminder(
            context = context,
            courseId = courseId,
            courseName = name,
            startMinute = startMinute,
            location = location,
            teacher = teacher,
            leadMinutes = lead,
        )
        com.kxin.classtable.data.Analytics.log("course_reminder_shown", "course_id" to courseId)
    }
}

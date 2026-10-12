package com.kxin.classtable.data

/**
 * 助手可以「把用户带到」的页面白名单。
 *
 * 导航本身不改数据,只是交接:像壁纸 / 备份 / 教务登录 / 系统权限这类必须由用户在原页面完成的
 * 事,助手最多把页面打开,不能假装已经完成。路由字符串与 MainActivity 的 NavHost 保持一致。
 */
object AiPages {

    data class Entry(val key: String, val label: String, val route: String)

    val entries: List<Entry> = listOf(
        Entry("TIMETABLE", "课表", "week"),
        Entry("COURSES", "课程列表", "courses"),
        Entry("AGENDA", "日程", "agenda"),
        Entry("SETTINGS", "设置首页", "settings"),
        Entry("TIMETABLE_DISPLAY", "课表显示", "timetable_display"),
        Entry("SCHEDULE_TIMES", "作息时间", "schedule_times"),
        Entry("SEMESTER", "学期周次", "semester"),
        Entry("ADJUSTMENTS", "调休课表", "adjustments"),
        Entry("WIDGETS", "桌面小组件", "widget_settings"),
        Entry("COURSE_REMINDER", "课程提醒", "course_reminder"),
        Entry("AGENDA_REMINDER", "日程提醒", "agenda_reminder"),
        Entry("AI", "AI 设置", "ai_key"),
        Entry("MEMORY", "助手记忆", "agent_memory"),
        Entry("ACCOUNT", "账号与数据", "account"),
        Entry("RAIN_CLASSROOM", "雨课堂", "rain_classroom"),
        Entry("IMPORT", "导入课表", "import"),
        Entry("PERMISSIONS", "提醒可靠性 / 权限", "permissions"),
        Entry("UPDATE", "检查更新", "update"),
    )

    fun find(key: String): Entry? = entries.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) }

    /** 给模型看的目录:`键(说明)`。 */
    fun describe(): String = entries.joinToString("、") { "${it.key}(${it.label})" }
}

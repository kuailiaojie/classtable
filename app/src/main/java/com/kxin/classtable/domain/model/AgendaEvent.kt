package com.kxin.classtable.domain.model

/** 日程分类。只影响标签文字与一条淡彩点缀,不承载语义(与课程淡彩同一套取色逻辑)。 */
enum class AgendaCategory(val label: String) {
    TODO("待办"),
    ACTIVITY("活动"),
    EXAM("考试"),
    HOMEWORK("作业"),
    OTHER("其他"),
}

/**
 * 分类决定条目落在「日程」还是「倒计时」页签 —— 这是两个页签唯一的分工依据:
 * 待办 / 活动是「要做的事」,考试 / 作业是「等着倒数的目标」,其他两边都出现。
 */
val AgendaCategory.showsInAgenda: Boolean
    get() = this != AgendaCategory.EXAM && this != AgendaCategory.HOMEWORK

val AgendaCategory.showsInCountdown: Boolean
    get() = this != AgendaCategory.TODO && this != AgendaCategory.ACTIVITY

/** 优先级。仅作排序与前缀点缀,不做颜色语义。 */
enum class AgendaPriority(val label: String) {
    NONE("无"),
    LOW("低"),
    MEDIUM("中"),
    HIGH("高"),
}

/**
 * 用户自建的日程 / 倒计时条目。
 *
 * 分类决定它落在「日程」还是「倒计时」页签(见 [showsInAgenda] / [showsInCountdown]);
 * 提醒则是条目自己的属性,与落在哪个页签无关。
 * 全天用 [allDay] 表示,此时时间部分无意义(取当天 0:00 起)。
 */
data class AgendaEvent(
    val id: String,
    val title: String,
    val category: AgendaCategory = AgendaCategory.TODO,
    val startAt: Long,
    val endAt: Long,
    val allDay: Boolean = false,
    val location: String = "",
    val note: String = "",
    val priority: AgendaPriority = AgendaPriority.NONE,
    /** 到点提醒(默认关闭 —— 不设就不打扰)。 */
    val remindEnabled: Boolean = false,
    /** 提前多少分钟提醒;仅非全天且 [remindEnabled] 时有意义(全天按设置里的固定时刻)。 */
    val remindLeadMinutes: Int = 10,
    val updatedAt: Long = 0L,
) {
    /** 是否已经结束。 */
    fun isPast(now: Long = System.currentTimeMillis()): Boolean = endAt < now

    /** 是否正在进行中。 */
    fun isOngoing(now: Long = System.currentTimeMillis()): Boolean = startAt <= now && now <= endAt
}

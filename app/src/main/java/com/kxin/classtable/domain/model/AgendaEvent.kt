package com.kxin.classtable.domain.model

/** 日程分类。只影响标签文字与一条淡彩点缀,不承载语义(与课程淡彩同一套取色逻辑)。 */
enum class AgendaCategory(val label: String) {
    TODO("待办"),
    ACTIVITY("活动"),
    EXAM("考试"),
    HOMEWORK("作业"),
    OTHER("其他"),
}

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
 * 一条数据两种看法:「议程」按天铺时间线,「倒计时」按剩余天数铺列表 —— 都是它。
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
    val updatedAt: Long = 0L,
) {
    /** 是否已经结束。 */
    fun isPast(now: Long = System.currentTimeMillis()): Boolean = endAt < now

    /** 是否正在进行中。 */
    fun isOngoing(now: Long = System.currentTimeMillis()): Boolean = startAt <= now && now <= endAt
}

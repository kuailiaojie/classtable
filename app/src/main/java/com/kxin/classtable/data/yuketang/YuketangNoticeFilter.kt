package com.kxin.classtable.data.yuketang

import android.util.Log
import com.kxin.classtable.data.local.AnnouncementDao
import com.kxin.classtable.data.local.AppDatabase

/**
 * 区分「雨课堂自己发的上课提醒」与「老师发的课程公告」。
 *
 * 雨课堂会在课前/开课时自己推「上课提醒 / 开始签到」这类消息,而课表本来就有课前提醒 ——
 * 这类消息再推一次纯属重复打扰,所以**新公告通知与课前提醒里都排除掉**。
 * 课程详情页的时间轴仍照实显示:那确实是雨课堂发过的内容,只是不该由我们再喊一遍。
 *
 * 只能按文案识别 —— 接口没有稳定的类型字段可依赖。词表刻意保守:宁可漏掉一条提醒,
 * 也不要把老师写的真公告(比如「下节课带计算器」)错杀。每条新公告的标题都会写进
 * logcat(tag `YuketangSync`),要调词表照着实际文案改 `CLASS_REMINDER_KEYWORDS` 即可。
 */
object YuketangNoticeFilter {

    private const val TAG = "YuketangSync"

    private val CLASS_REMINDER_KEYWORDS = listOf(
        "上课提醒",
        "开课提醒",
        "即将上课",
        "马上上课",
        "就要上课",
        "准备上课",
        "别忘了上课",
        "分钟后上课",
        "分钟后开始上课",
        "请及时签到",
        "已开始签到",
        "进入课堂",
    )

    fun isClassReminder(title: String, content: String): Boolean {
        val text = if (content.isBlank()) title else "$title $content"
        return CLASS_REMINDER_KEYWORDS.any { text.contains(it) }
    }

    /**
     * 某班级最新一条**非上课提醒**公告的标题(课前提醒用)。
     *
     * 往最近 [recentLookback] 条里找:如果最新的几条都是雨课堂的上课提醒,就继续往下找一条
     * 真正的公告;实在没有就返回 null(宁可不附,也不附一条「提醒你上课」)。
     */
    suspend fun latestTitle(
        dao: AnnouncementDao,
        classroomId: String,
        recentLookback: Int = 8,
    ): String? {
        val candidates = runCatching { dao.recentByClassroom(classroomId, recentLookback) }
            .getOrDefault(emptyList())
        val picked = candidates.firstOrNull { !isClassReminder(it.title, it.content) }
        if (picked == null && candidates.isNotEmpty()) {
            Log.i(TAG, "classroom=$classroomId 最近 ${candidates.size} 条都是雨课堂上课提醒,课前提醒不附公告")
        }
        return picked?.title?.takeIf { it.isNotBlank() }
    }

    /**
     * 课前提醒的入口:App 课程 id → 经绑定反查雨课堂班级 → 取最新一条非提醒公告的标题。
     *
     * 公告是按班级缓存的,所以提醒侧(闹钟接收器 / 实时活动服务 / FCM)都要先走这一步反查。
     * 未绑定该课程时返回 null(不附公告),这是正常情况而非错误。
     */
    suspend fun latestTitleForCourse(db: AppDatabase, courseId: String): String? {
        val classroomId = db.yuketangBindingDao().getByCourse(courseId)?.classroomId ?: return null
        return latestTitle(db.announcementDao(), classroomId)
    }
}

package com.kxin.classtable.data.yuketang

import com.kxin.classtable.data.local.AnnouncementDao
import com.kxin.classtable.data.local.AnnouncementEntity
import com.kxin.classtable.data.local.CourseDao
import com.kxin.classtable.data.local.YuketangBindingDao
import com.kxin.classtable.data.local.YuketangBindingEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次拉取的结果。
 *
 * [newCount] 只在**该班级此前已有缓存**时才统计新增:首次登录/首次拉取时把整批历史公告
 * 当「新公告」推给用户会是一次通知风暴,那不是「新」。
 */
data class AnnouncementSyncResult(
    val fetched: Int = 0,
    val newCount: Int = 0,
    val latestCourseName: String? = null,
    val latestTitle: String? = null,
)

/** 雨课堂绑定与公告的本机仓库。所有写操作都只落本机表,不参与云同步。 */
@Singleton
class YuketangRepository @Inject constructor(
    private val client: YuketangClient,
    private val sessionStore: YuketangSessionStore,
    private val bindingDao: YuketangBindingDao,
    private val announcementDao: AnnouncementDao,
    private val courseDao: CourseDao,
) {
    /** [loggedIn] 用:单例仓库自己持一个小作用域,随进程存活。 */
    private val loggedInScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun isLoggedIn(): Boolean = sessionStore.read() != null

    /**
     * 登录态流(登录/登出后各页面立刻跟着变)。
     *
     * 单例 + `Eagerly`:登录态是全局状态,不该因为某个页面订阅晚了就漏掉变化。
     */
    val loggedIn: StateFlow<Boolean> = sessionStore.session
        .map { it != null }
        .stateIn(loggedInScope, SharingStarted.Eagerly, sessionStore.read() != null)

    /** [verifyLogin] 的语义:false = 服务端明确说未登录;网络异常会抛 IOException。 */
    suspend fun verifyLogin(): Boolean = client.verify()

    /** 登出:清掉会话与公告缓存。绑定保留(课程↔班级与账号无关,重新登录不必再配一遍)。 */
    suspend fun logout() {
        sessionStore.write(null)
        announcementDao.clear()
    }

    suspend fun saveSession(session: YuketangSession) {
        sessionStore.write(session)
    }

    fun observeBindings(): Flow<List<YuketangBindingEntity>> = bindingDao.observeAll()

    fun observeAnnouncements(courseId: String): Flow<List<YuketangAnnouncement>> =
        announcementDao.observeByCourse(courseId).map { list -> list.map { it.toDomain() } }

    /** 拉取课程列表(仅绑定页需要;未登录会抛 [NotLoggedInException])。 */
    suspend fun classrooms(): List<YuketangCourse> = client.courses()

    /**
     * 自动匹配并补齐绑定。**已有绑定的课程一律跳过**——用户手动改过的对应关系不该被覆盖。
     *
     * @return 本次自动绑定的课程数。
     */
    suspend fun refreshBindings(): Int {
        val classrooms = client.courses()
        val courses = courseDao.getAll().map { it.toDomain() }
        val bound = bindingDao.getAll().map { it.courseId }.toSet()
        val now = System.currentTimeMillis()
        val fresh = YuketangMatcher.match(courses, classrooms)
            .filter { it.best != null && it.courseId !in bound }
            .map { match ->
                val classroom = match.best!!
                YuketangBindingEntity(
                    courseId = match.courseId,
                    classroomId = classroom.classroomId,
                    classroomName = classroom.name,
                    teacherName = classroom.teacherName,
                    updatedAt = now,
                )
            }
        if (fresh.isNotEmpty()) bindingDao.upsertAll(fresh)
        return fresh.size
    }

    suspend fun bind(courseId: String, classroom: YuketangCourse) {
        bindingDao.upsert(
            YuketangBindingEntity(
                courseId = courseId,
                classroomId = classroom.classroomId,
                classroomName = classroom.name,
                teacherName = classroom.teacherName,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun unbind(courseId: String) = bindingDao.deleteByCourse(courseId)

    /**
     * 遍历已绑定课程拉取公告并 upsert。
     *
     * 单个班级失败不影响其它班级;但**登录失效要上抛**——否则后台任务会当作「拉到了 0 条」
     * 一直静默重试。
     */
    suspend fun syncAnnouncements(): AnnouncementSyncResult {
        val bindings = bindingDao.getAll()
        if (bindings.isEmpty()) return AnnouncementSyncResult()
        val courseNames = courseDao.getAll().associate { it.id to it.name }
        val now = System.currentTimeMillis()

        var fetched = 0
        var newCount = 0
        var latestCourseName: String? = null
        var latestTitle: String? = null
        var latestAt = 0L

        for (binding in bindings) {
            val announcements = try {
                client.announcements(binding.classroomId)
            } catch (e: NotLoggedInException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            if (announcements.isEmpty()) continue

            // 首次拉取这个班级:整批当基线,不报「新」。
            val hadCache = announcementDao.countByClassroom(binding.classroomId) > 0
            if (hadCache) {
                val fresh = announcements.filter { announcementDao.getById(it.id) == null }
                newCount += fresh.size
                fresh.maxByOrNull { it.createdAtMillis }?.let { newest ->
                    if (newest.createdAtMillis >= latestAt) {
                        latestAt = newest.createdAtMillis
                        latestTitle = newest.title
                        latestCourseName = courseNames[binding.courseId]
                    }
                }
            }

            announcementDao.upsertAll(
                announcements.map {
                    AnnouncementEntity.fromDomain(it, courseId = binding.courseId, fetchedAt = now)
                },
            )
            fetched += announcements.size
        }
        return AnnouncementSyncResult(
            fetched = fetched,
            newCount = newCount,
            latestCourseName = latestCourseName,
            latestTitle = latestTitle,
        )
    }
}

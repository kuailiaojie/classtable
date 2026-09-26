package com.kxin.classtable.data

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.kxin.classtable.data.local.CourseDao
import com.kxin.classtable.data.local.CourseEntity
import com.kxin.classtable.data.local.DeletedCourseDao
import com.kxin.classtable.data.local.DeletedCourseEntity
import com.kxin.classtable.domain.CourseHuePlanner
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.CourseColorScheme
import com.kxin.classtable.notify.ReminderPlanner
import com.kxin.classtable.widget.NextClassWidget
import com.kxin.classtable.widget.TodayWidget
import com.kxin.classtable.widget.TomorrowWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 课程仓库:Room 为本地真相,写操作后触发 Firestore 同步 + 小组件刷新 + 提醒重排。 */
@Singleton
class CourseRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: CourseDao,
    private val deletedDao: DeletedCourseDao,
    private val sync: SyncRepository,
    private val reminderPlanner: ReminderPlanner,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun observeAll(): Flow<List<Course>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    /** 星期 day(1=周一)当天有课的课程流。 */
    fun observeByWeekday(weekday: Int): Flow<List<Course>> =
        dao.observeByWeekday(1 shl (weekday - 1)).map { list -> list.map { it.toDomain() } }

    suspend fun get(id: String): Course? = dao.getById(id)?.toDomain()

    suspend fun save(course: Course) {
        val isNew = dao.getById(course.id) == null
        val stored = course.withPinnedHue()
        dao.upsert(CourseEntity.fromDomain(stored.copy(updatedAt = System.currentTimeMillis())))
        postChangeSideEffects()
        Analytics.log(
            if (isNew) "course_created" else "course_updated",
            "course_id" to course.id,
        )
        // 同步放到后台:本地已落库即返回,不阻塞调用方(如保存后返回上一层)
        scope.launch { sync.syncNow() }
    }

    suspend fun delete(id: String) {
        dao.deleteById(id)
        // 墓碑:向远端传播删除,防止下次 pull 时课程"复活"
        deletedDao.upsert(DeletedCourseEntity(id, System.currentTimeMillis()))
        Analytics.log("course_deleted", "course_id" to id)
        // 取消该课程已排的提醒 + 云同步,均在后台完成,不阻塞导航
        scope.launch {
            runCatching { reminderPlanner.cancelCourse(id) }
            runCatching { sync.syncNow() }
        }
        postChangeSideEffects()
    }

    /**
     * 批量删除:墓碑、刷新、同步都只收尾一轮。
     *
     * 逐条调用 [delete] 也能对,但会触发 N 次小组件刷新与提醒重排 —— 一次删十门课就是十轮。
     */
    suspend fun deleteCourses(ids: List<String>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        ids.forEach { id ->
            dao.deleteById(id)
            // 墓碑:向远端传播删除,防止下次 pull 时课程"复活"
            deletedDao.upsert(DeletedCourseEntity(id, now))
            Analytics.log("course_deleted", "course_id" to id)
        }
        postChangeSideEffects()
        scope.launch { sync.syncNow() }
    }

    suspend fun importAll(courses: List<Course>) {
        val now = System.currentTimeMillis()
        // 导入即钉住时刻:按当前作息(导入前刚被脚本覆盖的那一张)把节次换算成具体时刻存下来,
        // 之后改作息不会再把这些课程的时间点带跑。
        val periods = Schedule.parsePeriods(settingsRepository.currentSettings().periodTimes)

        // 配色同理,导入即钉住色相:同批同名的课(周一一条、周五一条)分到同一个色相,
        // 与已有课程也不重复 —— 之后增删课程都不会把它们带跑。
        val scheme = currentScheme()
        val assigned = LinkedHashMap<String, Int>()
        dao.getAll().map { it.toDomain() }.forEach { existing ->
            val hue = existing.colorHue
            if (existing.colorHex.isBlank() && hue != null) {
                assigned.putIfAbsent(CourseHuePlanner.seedOf(existing), hue)
            }
        }
        val taken = assigned.values.toMutableList()
        val pinned = courses.map { course ->
            if (course.colorHue != null || course.colorHex.isNotBlank()) {
                course
            } else {
                val seed = CourseHuePlanner.seedOf(course)
                course.copy(
                    colorHue = assigned.getOrPut(seed) {
                        CourseHuePlanner.next(seed, taken, scheme).also { taken.add(it) }
                    },
                )
            }
        }

        dao.upsertAll(pinned.map { Schedule.pinCourseTimes(it, periods) }
            .map { CourseEntity.fromDomain(it.copy(updatedAt = now)) })
        postChangeSideEffects()
        Analytics.log("courses_imported", "count" to courses.size)
        scope.launch { sync.syncNow() }
    }

    /**
     * 按当前配色方案把全部课程的色相重排一遍,返回改动的课程数。
     *
     * 分配从零开始、按课名排序推进,所以同一份课表每次重排的结果完全一样。
     * **用户自己指定过颜色的课程保持不动** —— 那不是自动配色该改的东西。
     */
    suspend fun reassignAllColors(): Int {
        val targets = dao.getAll().map { it.toDomain() }.filter { it.colorHex.isBlank() }
        if (targets.isEmpty()) return 0

        val scheme = currentScheme()
        val seeds = targets.map { CourseHuePlanner.seedOf(it) }.distinct().sorted()
        val taken = mutableListOf<Int>()
        val assigned = LinkedHashMap<String, Int>(seeds.size)
        seeds.forEach { seed ->
            val hue = CourseHuePlanner.next(seed, taken, scheme)
            assigned[seed] = hue
            taken.add(hue)
        }

        val now = System.currentTimeMillis()
        val updated = targets.map {
            it.copy(colorHue = assigned.getValue(CourseHuePlanner.seedOf(it)), updatedAt = now)
        }
        dao.upsertAll(updated.map { CourseEntity.fromDomain(it) })
        postChangeSideEffects()
        scope.launch { sync.syncNow() }
        return updated.size
    }

    /**
     * 给还没有色相的课程钉一个:自带色相、或用户指定过颜色的课程直接沿用。
     *
     * 同名课沿用同一个色相 —— 一门课分成多条记录时它们要能对上号。
     */
    private suspend fun Course.withPinnedHue(): Course {
        if (colorHue != null || colorHex.isNotBlank()) return this
        val others = dao.getAll().map { it.toDomain() }.filter { it.id != id }
        val seed = CourseHuePlanner.seedOf(this)
        val sameName = others.firstOrNull {
            it.colorHex.isBlank() && it.colorHue != null && CourseHuePlanner.seedOf(it) == seed
        }
        val hue = sameName?.colorHue
            ?: CourseHuePlanner.next(seed, takenHues(others), currentScheme())
        return copy(colorHue = hue)
    }

    /** 已被占用的色相:只有走自动配色的课程占位,指定过颜色的课程不占。 */
    private fun takenHues(courses: List<Course>): List<Int> =
        courses.filter { it.colorHex.isBlank() }.mapNotNull { it.colorHue }

    private suspend fun currentScheme(): CourseColorScheme =
        TimetablePrefsStore.flow(context).first().colorScheme

    /** 课程数据变更后的副作用:刷新小组件 + 重排提醒(均在 IO,避免阻塞调用线程)。 */
    private fun postChangeSideEffects() {
        refreshWidgets()
        scope.launch { reminderPlanner.rescheduleAll() }
    }

    private fun refreshWidgets() {
        scope.launch {
            runCatching { NextClassWidget().updateAll(context) }
            runCatching { TodayWidget().updateAll(context) }
            runCatching { TomorrowWidget().updateAll(context) }
        }
    }
}

package com.kxin.classtable.data

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.kxin.classtable.data.local.CourseDao
import com.kxin.classtable.data.local.CourseEntity
import com.kxin.classtable.data.local.DeletedCourseDao
import com.kxin.classtable.data.local.DeletedCourseEntity
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.notify.ReminderPlanner
import com.kxin.classtable.widget.NextClassWidget
import com.kxin.classtable.widget.TodayWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
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
        dao.upsert(CourseEntity.fromDomain(course.copy(updatedAt = System.currentTimeMillis())))
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

    suspend fun importAll(courses: List<Course>) {
        val now = System.currentTimeMillis()
        dao.upsertAll(courses.map { CourseEntity.fromDomain(it.copy(updatedAt = now)) })
        postChangeSideEffects()
        Analytics.log("courses_imported", "count" to courses.size)
        scope.launch { sync.syncNow() }
    }

    /** 课程数据变更后的副作用:刷新小组件 + 重排提醒(均在 IO,避免阻塞调用线程)。 */
    private fun postChangeSideEffects() {
        refreshWidgets()
            scope.launch { reminderPlanner.rescheduleAll() }
    }

    private fun refreshWidgets() {
        scope.launch {
            runCatching { NextClassWidget().updateAll(context) }
            runCatching { TodayWidget().updateAll(context) }
        }
    }
}

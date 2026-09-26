package com.kxin.classtable.data

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.kxin.classtable.data.local.AgendaDao
import com.kxin.classtable.data.local.AgendaEntity
import com.kxin.classtable.data.local.DeletedAgendaDao
import com.kxin.classtable.data.local.DeletedAgendaEntity
import com.kxin.classtable.domain.model.AgendaEvent
import com.kxin.classtable.notify.ReminderPlanner
import com.kxin.classtable.widget.AgendaWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 日程 / 倒计时的本地仓库(与 [CourseRepository] 同一套路:本地优先,写入后后台触发云同步)。
 *
 * 与课程一样,日程的增删改也有两个副作用:**重排提醒**与**刷新桌面小组件**。
 */
@Singleton
class AgendaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AgendaDao,
    private val deletedDao: DeletedAgendaDao,
    private val sync: SyncRepository,
    private val reminderPlanner: ReminderPlanner,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun observeAll(): Flow<List<AgendaEvent>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun get(id: String): AgendaEvent? = dao.getById(id)?.toDomain()

    suspend fun save(event: AgendaEvent) {
        val stamped = event.copy(updatedAt = System.currentTimeMillis())
        dao.upsert(AgendaEntity.fromDomain(stamped))
        scope.launch { runCatching { sync.syncNow() } }
        postChangeSideEffects()
    }

    suspend fun delete(id: String) {
        dao.deleteById(id)
        deletedDao.upsert(DeletedAgendaEntity(id, System.currentTimeMillis()))
        scope.launch { runCatching { sync.syncNow() } }
        reminderPlanner.cancelAgenda(id)
        postChangeSideEffects()
    }

    private fun postChangeSideEffects() {
        scope.launch { runCatching { reminderPlanner.rescheduleAll() } }
        scope.launch { runCatching { AgendaWidget().updateAll(context) } }
    }
}

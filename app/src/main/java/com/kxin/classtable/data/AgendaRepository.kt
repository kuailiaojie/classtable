package com.kxin.classtable.data

import com.kxin.classtable.data.local.AgendaDao
import com.kxin.classtable.data.local.AgendaEntity
import com.kxin.classtable.data.local.DeletedAgendaDao
import com.kxin.classtable.data.local.DeletedAgendaEntity
import com.kxin.classtable.domain.model.AgendaEvent
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
 * 与课程不同,日程没有小组件与提醒的副作用 —— 它只是用户自己记下的事。
 */
@Singleton
class AgendaRepository @Inject constructor(
    private val dao: AgendaDao,
    private val deletedDao: DeletedAgendaDao,
    private val sync: SyncRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun observeAll(): Flow<List<AgendaEvent>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun get(id: String): AgendaEvent? = dao.getById(id)?.toDomain()

    suspend fun save(event: AgendaEvent) {
        val stamped = event.copy(updatedAt = System.currentTimeMillis())
        dao.upsert(AgendaEntity.fromDomain(stamped))
        scope.launch { runCatching { sync.syncNow() } }
    }

    suspend fun delete(id: String) {
        dao.deleteById(id)
        deletedDao.upsert(DeletedAgendaEntity(id, System.currentTimeMillis()))
        scope.launch { runCatching { sync.syncNow() } }
    }
}

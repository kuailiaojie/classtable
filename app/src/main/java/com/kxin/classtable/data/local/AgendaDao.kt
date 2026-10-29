package com.kxin.classtable.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AgendaDao {
    @Query("SELECT * FROM agenda_events ORDER BY startAt")
    fun observeAll(): Flow<List<AgendaEntity>>

    @Query("SELECT * FROM agenda_events WHERE courseId = :courseId ORDER BY startAt")
    fun observeByCourse(courseId: String): Flow<List<AgendaEntity>>

    @Query("SELECT * FROM agenda_events")
    suspend fun getAll(): List<AgendaEntity>

    @Query("SELECT * FROM agenda_events WHERE id = :id")
    suspend fun getById(id: String): AgendaEntity?

    @Query("UPDATE agenda_events SET completed = :completed, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateCompleted(id: String, completed: Boolean, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: AgendaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<AgendaEntity>)

    @Query("DELETE FROM agenda_events WHERE id = :id")
    suspend fun deleteById(id: String)
}

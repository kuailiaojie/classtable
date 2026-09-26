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

    @Query("SELECT * FROM agenda_events")
    suspend fun getAll(): List<AgendaEntity>

    @Query("SELECT * FROM agenda_events WHERE id = :id")
    suspend fun getById(id: String): AgendaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: AgendaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<AgendaEntity>)

    @Query("DELETE FROM agenda_events WHERE id = :id")
    suspend fun deleteById(id: String)
}

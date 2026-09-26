package com.kxin.classtable.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 日程的删除墓碑:本地删除的 id + 时间,用于向远端传播删除(防复活)。 */
@Entity(tableName = "deleted_agenda")
data class DeletedAgendaEntity(
    @PrimaryKey val id: String,
    val updatedAt: Long,
)

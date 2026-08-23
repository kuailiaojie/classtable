package com.kxin.classtable.domain.model

data class Semester(
    val id: String,
    val name: String,        // 如 2026-2027-1
    val weekCount: Int = 20,
    val startDay: Long = 0L, // epochDay,用于推导当前周
)

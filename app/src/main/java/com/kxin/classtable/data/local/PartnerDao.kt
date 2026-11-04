package com.kxin.classtable.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PartnerDao {

    @Query("SELECT * FROM partner_profiles ORDER BY updatedAt DESC")
    fun observeAllProfiles(): Flow<List<PartnerProfileEntity>>

    @Query("SELECT * FROM partner_profiles ORDER BY updatedAt DESC")
    suspend fun getAllProfiles(): List<PartnerProfileEntity>

    @Query("SELECT * FROM partner_profiles WHERE isCurrent = 1 LIMIT 1")
    fun observeCurrentProfile(): Flow<PartnerProfileEntity?>

    @Query("SELECT * FROM partner_profiles WHERE id = :id LIMIT 1")
    suspend fun getProfile(id: String): PartnerProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: PartnerProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfiles(profiles: List<PartnerProfileEntity>)

    @Query("DELETE FROM partner_profiles WHERE id = :id")
    suspend fun deleteProfile(id: String)

    @Query("DELETE FROM partner_profiles")
    suspend fun deleteAllProfiles()

    @Query("UPDATE partner_profiles SET isCurrent = 0")
    suspend fun clearCurrent()

    @Query("UPDATE partner_profiles SET isCurrent = 1 WHERE id = :id")
    suspend fun setCurrent(id: String)

    @Transaction
    suspend fun setActiveProfile(id: String?) {
        clearCurrent()
        if (id != null) {
            setCurrent(id)
        }
    }

    @Query("SELECT * FROM partner_courses")
    fun observeAllCourses(): Flow<List<PartnerCourseEntity>>

    @Query("SELECT * FROM partner_courses")
    suspend fun getAllCourses(): List<PartnerCourseEntity>

    @Query("SELECT * FROM partner_courses WHERE partnerId = :partnerId")
    fun observeCourses(partnerId: String): Flow<List<PartnerCourseEntity>>

    @Query("SELECT * FROM partner_courses WHERE partnerId = :partnerId")
    suspend fun getCourses(partnerId: String): List<PartnerCourseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourses(courses: List<PartnerCourseEntity>)

    @Query("DELETE FROM partner_courses WHERE partnerId = :partnerId")
    suspend fun deleteCoursesByPartner(partnerId: String)

    @Query("DELETE FROM partner_courses")
    suspend fun deleteAllCourses()

    @Transaction
    suspend fun savePartnerWithCourses(
        profile: PartnerProfileEntity,
        courses: List<PartnerCourseEntity>,
    ) {
        insertProfile(profile)
        deleteCoursesByPartner(profile.id)
        insertCourses(courses)
        clearCurrent()
        setCurrent(profile.id)
    }

    @Transaction
    suspend fun deletePartnerFully(partnerId: String) {
        deleteCoursesByPartner(partnerId)
        deleteProfile(partnerId)
        val remaining = getAllProfiles()
        if (remaining.isNotEmpty() && remaining.none { it.isCurrent }) {
            setCurrent(remaining.first().id)
        }
    }

    @Transaction
    suspend fun replaceAllPartners(
        profiles: List<PartnerProfileEntity>,
        courses: List<PartnerCourseEntity>,
    ) {
        deleteAllCourses()
        deleteAllProfiles()
        insertProfiles(profiles)
        insertCourses(courses)
    }
}

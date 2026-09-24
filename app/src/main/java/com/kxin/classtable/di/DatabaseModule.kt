package com.kxin.classtable.di

import android.content.Context
import com.kxin.classtable.data.local.AnnouncementDao
import com.kxin.classtable.data.local.AppDatabase
import com.kxin.classtable.data.local.CourseDao
import com.kxin.classtable.data.local.DeletedCourseDao
import com.kxin.classtable.data.local.SemesterDao
import com.kxin.classtable.data.local.YuketangBindingDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.get(context)

    @Provides
    fun provideCourseDao(db: AppDatabase): CourseDao = db.courseDao()

    @Provides
    fun provideSemesterDao(db: AppDatabase): SemesterDao = db.semesterDao()

    @Provides
    fun provideDeletedCourseDao(db: AppDatabase): DeletedCourseDao = db.deletedCourseDao()

    @Provides
    fun provideYuketangBindingDao(db: AppDatabase): YuketangBindingDao = db.yuketangBindingDao()

    @Provides
    fun provideAnnouncementDao(db: AppDatabase): AnnouncementDao = db.announcementDao()
}

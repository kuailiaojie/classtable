package com.kxin.classtable.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CourseEntity::class, SemesterEntity::class, DeletedCourseEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun semesterDao(): SemesterDao
    abstract fun deletedCourseDao(): DeletedCourseDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v1 → v2:课程表增加自定义时间列(非作息时间课程)。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE courses ADD COLUMN customStartMinute INTEGER")
                db.execSQL("ALTER TABLE courses ADD COLUMN customEndMinute INTEGER")
            }
        }

        /** v2 → v3:星期多选位掩码 + 课程备注。旧数据按原 weekday 推导位掩码。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE courses ADD COLUMN weekdays INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE courses ADD COLUMN note TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE courses SET weekdays = (1 << (weekday - 1)) WHERE weekdays = 0")
            }
        }

        /**
         * v3 → v4:自定义周次的精确集合(CSV)。旧数据的自定义仍是连续范围,读的时候回退到
         * weekStart..weekEnd,所以这里**不回填**。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE courses ADD COLUMN weeks TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "classtable.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}

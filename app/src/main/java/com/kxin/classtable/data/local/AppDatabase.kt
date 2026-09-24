package com.kxin.classtable.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CourseEntity::class,
        SemesterEntity::class,
        DeletedCourseEntity::class,
        YuketangBindingEntity::class,
        AnnouncementEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun semesterDao(): SemesterDao
    abstract fun deletedCourseDao(): DeletedCourseDao
    abstract fun yuketangBindingDao(): YuketangBindingDao
    abstract fun announcementDao(): AnnouncementDao

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

        /**
         * v4 → v5:雨课堂的两张**本机**表——课程↔班级绑定、已拉取的课程公告。
         * 都是新建空表,不涉及旧数据回填。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `yuketang_bindings` (" +
                        "`courseId` TEXT NOT NULL, " +
                        "`classroomId` TEXT NOT NULL, " +
                        "`classroomName` TEXT NOT NULL, " +
                        "`teacherName` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`courseId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `announcements` (" +
                        "`id` TEXT NOT NULL, " +
                        "`classroomId` TEXT NOT NULL, " +
                        "`courseId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`publisher` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_announcements_courseId` " +
                        "ON `announcements` (`courseId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_announcements_classroomId` " +
                        "ON `announcements` (`classroomId`)",
                )
            }
        }

        /**
         * v5 → v6:公告缓存改为**按雨课堂班级**归属(去掉 courseId 列)。
         *
         * 原来公告挂在 App 课程 id 上,而同一门课在课表里可能有多条记录(周一一条、周五一条),
         * 它们绑定同一个班级 —— 于是同一条公告会被写上两个 courseId,`id` 做主键时后写的把先写的
         * 顶掉,只有一条记录能看到公告。改成按班级归属后,多条记录共享同一份。
         *
         * 公告是纯缓存,直接重建空表即可(下次拉取会补回来),不必逐行搬迁。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `announcements`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `announcements` (" +
                        "`id` TEXT NOT NULL, " +
                        "`classroomId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`publisher` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_announcements_classroomId_createdAt` " +
                        "ON `announcements` (`classroomId`, `createdAt`)",
                )
            }

            /** v6 → v7:课程支持用户自定义颜色。 */
            val MIGRATION_6_7 = object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE courses ADD COLUMN colorHex TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "classtable.db",
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7,
                    )
                    .build()
                    .also { instance = it }
            }
    }
}

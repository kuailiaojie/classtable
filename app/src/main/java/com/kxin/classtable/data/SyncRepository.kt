package com.kxin.classtable.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.kxin.classtable.data.local.CourseDao
import com.kxin.classtable.data.local.CourseEntity
import com.kxin.classtable.data.local.DeletedCourseDao
import com.kxin.classtable.domain.model.Course
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地优先 + Firestore 同步:
 * 未登录 = 纯本地;登录后 pull 远端 → 合并(updatedAt 后者胜)→ 应用删除墓碑 → push 本地。
 * 删除通过「墓碑」传播,避免远端删除在下次 pull 时复活。
 */
@Singleton
class SyncRepository @Inject constructor(
    private val courseDao: CourseDao,
    private val deletedDao: DeletedCourseDao,
) {
    private val db = FirebaseFirestore.getInstance()

    private fun coursesCol(uid: String) =
        db.collection("users").document(uid).collection("courses")

    private fun deletedCol(uid: String) =
        db.collection("users").document(uid).collection("deleted")

    suspend fun syncNow(): Result<Unit> = runCatching {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.success(Unit)

        // 1) 拉远端:课程 + 删除墓碑
        val remoteCourses = coursesCol(uid).get().await()
            .documents.mapNotNull { it.toObject(RemoteCourse::class.java)?.toDomain() }
        val remoteDeleted = deletedCol(uid).get().await()
            .documents.mapNotNull { d -> d.id to (d.getLong("updatedAt") ?: 0L) }

        // 2) 合并(updatedAt 后者胜),并应用远端 + 本地墓碑
        val local = courseDao.getAll().map { it.toDomain() }
        val localTombstones = deletedDao.getAll().associate { it.id to it.updatedAt }
        val merged = merge(local, remoteCourses).filterNot { c ->
            remoteDeleted.any { t -> t.first == c.id && t.second >= c.updatedAt } ||
                (localTombstones[c.id]?.let { it >= c.updatedAt } == true)
        }

        // 3) 写回本地:覆盖式 upsert + 清理多余行
        courseDao.upsertAll(merged.map { CourseEntity.fromDomain(it) })
        val finalIds = merged.map { it.id }.toSet()
        courseDao.getAll().forEach { e -> if (e.id !in finalIds) courseDao.deleteById(e.id) }

        // 4) 推远端:课程 + 墓碑(幂等)
        val batch = db.batch()
        merged.forEach { c -> batch.set(coursesCol(uid).document(c.id), RemoteCourse.fromDomain(c)) }
        deletedDao.getAll().forEach { t ->
            batch.set(deletedCol(uid).document(t.id), mapOf("updatedAt" to t.updatedAt))
        }
        batch.commit().await()

        Result.success(Unit)
    }

    private fun merge(local: List<Course>, remote: List<Course>): List<Course> {
        val byId = HashMap<String, Course>()
        local.forEach { byId[it.id] = it }
        remote.forEach { r ->
            val l = byId[r.id]
            if (l == null || r.updatedAt > l.updatedAt) byId[r.id] = r
        }
        return byId.values.toList()
    }
}

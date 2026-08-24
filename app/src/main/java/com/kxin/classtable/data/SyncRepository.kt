package com.kxin.classtable.data

import com.kxin.classtable.data.local.CourseDao
import com.kxin.classtable.data.local.CourseEntity
import com.kxin.classtable.data.local.DeletedCourseDao
import com.kxin.classtable.domain.model.Course
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地优先 + Firestore 同步(经反代 REST):
 * 未登录 = 纯本地;登录后 pull 远端 → 合并(updatedAt 后者胜)→ 应用删除墓碑 → push 本地。
 * 删除通过「墓碑」传播,避免远端删除在下次 pull 时复活。
 *
 * 与旧 SDK 版差异:Firestore REST 无实时监听,同步为「按需 pull/push」;
 * 请求带 ID token Bearer,远端安全规则(request.auth.uid == userId)照常评估。
 */
@Singleton
class SyncRepository @Inject constructor(
    private val courseDao: CourseDao,
    private val deletedDao: DeletedCourseDao,
    private val gateway: FirebaseGateway,
    private val authRepository: AuthRepository,
) {
    private fun dbBase() = "v1/projects/${gateway.projectId}/databases/(default)/documents"

    private fun coursesCol(uid: String) = "${dbBase()}/users/$uid/courses"

    private fun deletedCol(uid: String) = "${dbBase()}/users/$uid/deleted"

    suspend fun syncNow(): Result<Unit> = runCatching {
        val uid = authRepository.uid ?: return Result.success(Unit)
        val token = authRepository.freshIdToken() ?: return Result.success(Unit)

        // 1) 拉远端:课程 + 删除墓碑
        val remoteCourses = pullDocuments(coursesCol(uid), token)
            .mapNotNull { doc -> RemoteCourse.fromFields(doc.optJSONObject("fields") ?: return@mapNotNull null).toDomain() }
        val remoteDeleted = pullDocuments(deletedCol(uid), token)
            .mapNotNull { doc ->
                val id = doc.optString("id")
                val updatedAt = doc.optJSONObject("fields")
                    ?.optJSONObject("updatedAt")?.optString("integerValue")?.toLongOrNull()
                if (id.isBlank() || updatedAt == null) null else id to updatedAt
            }

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

        // 4) 推远端:课程 + 墓碑(幂等,单次 commit)
        val writes = JSONArray()
        merged.forEach { c ->
            writes.put(
                JSONObject().put(
                    "update",
                    JSONObject()
                        .put("name", coursesCol(uid) + "/" + encodeSegment(c.id))
                        .put("fields", RemoteCourse.toFields(RemoteCourse.fromDomain(c))),
                ),
            )
        }
        deletedDao.getAll().forEach { t ->
            writes.put(
                JSONObject().put(
                    "update",
                    JSONObject()
                        .put("name", deletedCol(uid) + "/" + encodeSegment(t.id))
                        .put("fields", JSONObject().put("updatedAt", JSONObject().put("integerValue", t.updatedAt.toString()))),
                ),
            )
        }
        if (writes.length() > 0) {
            gateway.firestore(
                "${dbBase()}:commit",
                "POST",
                JSONObject().put("writes", writes),
                token,
            )
        }

        Result.success(Unit)
    }

    /** 拉集合全部文档,返回 [{id, fields}]。pageSize=300 覆盖课程量级,无需翻页。 */
    private suspend fun pullDocuments(colPath: String, token: String): List<JSONObject> {
        val resp = gateway.firestore("$colPath?pageSize=300", "GET", null, token)
        val docs = resp.optJSONArray("documents") ?: return emptyList()
        val out = ArrayList<JSONObject>(docs.length())
        for (i in 0 until docs.length()) {
            val doc = docs.optJSONObject(i) ?: continue
            val fields = doc.optJSONObject("fields") ?: continue
            out.add(JSONObject().put("id", doc.optString("name").substringAfterLast('/')).put("fields", fields))
        }
        return out
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

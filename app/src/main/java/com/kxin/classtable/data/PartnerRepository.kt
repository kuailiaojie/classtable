package com.kxin.classtable.data

import com.kxin.classtable.data.local.PartnerCourseEntity
import com.kxin.classtable.data.local.PartnerDao
import com.kxin.classtable.data.local.PartnerProfileEntity
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.WeekSpec
import com.kxin.classtable.domain.enumOrDefault
import com.kxin.classtable.domain.model.CommonFreeSlot
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.PartnerProfile
import com.kxin.classtable.domain.model.PartnerType
import com.kxin.classtable.domain.model.WeekType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PartnerRepository @Inject constructor(
    private val partnerDao: PartnerDao,
    private val gateway: FirebaseGateway,
) {
    /** Firestore REST 请求路径基址（含 v1 版本前缀）。 */
    private fun dbBase() = "v1/projects/${gateway.projectId}/databases/(default)/documents"

    /** commit 请求体里 write.update.name 的资源名基址（Firestore 要求以 "projects" 开头）。 */
    private fun docBase() = "projects/${gateway.projectId}/databases/(default)/documents"

    private fun sharePath(code: String) = "${dbBase()}/shares/$code"

    private fun shareDocName(code: String) = "${docBase()}/shares/$code"

    /** 观察所有关注的伴侣/朋友列表。 */
    fun observeProfiles(): Flow<List<PartnerProfile>> =
        partnerDao.observeAllProfiles().map { list -> list.map { it.toDomain() } }

    /** 观察每位伴侣/朋友的课程门数映射。 */
    fun observeCourseCounts(): Flow<Map<String, Int>> =
        partnerDao.observeAllCourses().map { list ->
            list.groupingBy { it.partnerId }.eachCount()
        }

    /** 观察当前选中的伴侣档案。 */
    fun observeActiveProfile(): Flow<PartnerProfile?> =
        partnerDao.observeCurrentProfile().map { it?.toDomain() }

    /** 观察当前选中伴侣的课程列表（已装配伴侣属性）。 */
    fun observeActivePartnerCourses(): Flow<List<Course>> =
        observeActiveProfile().flatMapLatest { partner ->
            if (partner == null) {
                flowOf(emptyList())
            } else {
                partnerDao.observeCourses(partner.id).map { list ->
                    list.map { it.toDomain(partner) }
                }
            }
        }

    suspend fun getProfile(id: String): PartnerProfile? =
        partnerDao.getProfile(id)?.toDomain()

    suspend fun setActivePartner(id: String?) {
        partnerDao.setActiveProfile(id)
    }

    suspend fun updateProfile(profile: PartnerProfile) {
        partnerDao.insertProfile(
            PartnerProfileEntity.fromDomain(
                profile.copy(updatedAt = System.currentTimeMillis()),
            ),
        )
    }

    suspend fun deletePartner(id: String) {
        partnerDao.deletePartnerFully(id)
    }

    suspend fun savePartner(profile: PartnerProfile, courses: List<Course>) {
        val profileEntity = PartnerProfileEntity.fromDomain(profile)
        val courseEntities = courses.map { PartnerCourseEntity.fromDomain(it, profile.id) }
        partnerDao.savePartnerWithCourses(profileEntity, courseEntities)
    }

    /**
     * 把云端提取码对应的课表入库（若同名档案已存在则覆盖更新其课程，保留原有关系与配色偏好）。
     */
    suspend fun importFromCloudCode(
        code: String,
        idToken: String,
    ): Result<Pair<PartnerProfile, Int>> = runCatching {
        val cleanCode = normalizeShareCode(code)
        val (parsedProfile, parsedCourses) = pullShareFromCloud(cleanCode, idToken).getOrThrow()
        saveImported(parsedProfile, parsedCourses, shareCode = cleanCode)
    }

    private suspend fun saveImported(
        parsedProfile: PartnerProfile,
        parsedCourses: List<Course>,
        shareCode: String? = null,
    ): Pair<PartnerProfile, Int> {
        val existing = partnerDao.getAllProfiles().firstOrNull {
            it.name.equals(parsedProfile.name, ignoreCase = true)
        }?.toDomain()

        val finalProfile = if (existing != null) {
            existing.copy(
                isCurrent = true,
                shareCode = shareCode ?: existing.shareCode,
                updatedAt = System.currentTimeMillis(),
            )
        } else {
            parsedProfile.copy(
                isCurrent = true,
                shareCode = shareCode ?: parsedProfile.shareCode,
                updatedAt = System.currentTimeMillis(),
            )
        }
        val reboundCourses = parsedCourses.map { c ->
            c.copy(
                semesterId = finalProfile.id,
                colorHue = finalProfile.colorHue,
                isPartner = true,
                ownerName = finalProfile.name,
            )
        }
        savePartner(finalProfile, reboundCourses)
        return finalProfile to reboundCourses.size
    }

    /**
     * 把课表发布到云端并生成 6 位提取码（需已登录，uid / idToken 由调用方传入）。
     * 提取码冲突时自动换一个重试，最多 [CODE_ATTEMPTS] 次。
     */
    suspend fun publishShareToCloud(
        uid: String,
        idToken: String,
        ownerName: String,
        type: PartnerType,
        colorHue: Int,
        courses: List<Course>,
        obscureCourseDetails: Boolean = false,
    ): Result<String> = runCatching {
        val payload = createShareJson(
            ownerName = ownerName,
            type = type,
            colorHue = colorHue,
            courses = courses,
            obscureCourseDetails = obscureCourseDetails,
        )
        val fields = JSONObject().apply {
            FirestoreFields.putString(this, "creatorUid", uid)
            FirestoreFields.putString(this, "ownerName", ownerName.trim().ifEmpty { "我的课表" })
            FirestoreFields.putString(this, "type", type.name)
            FirestoreFields.putInt(this, "colorHue", colorHue)
            FirestoreFields.putString(this, "payload", payload)
            FirestoreFields.putLong(this, "createdAt", System.currentTimeMillis())
        }

        var lastConflict: FirebaseApiException? = null
        for (attempt in 0 until CODE_ATTEMPTS) {
            val code = randomShareCode()
            // currentDocument.exists=false：撞码时不覆盖别人的课表，换一个重试
            val write = JSONObject()
                .put(
                    "update",
                    JSONObject().put("name", shareDocName(code)).put("fields", fields),
                )
                .put("currentDocument", JSONObject().put("exists", false))
            try {
                gateway.firestore(
                    "${dbBase()}:commit",
                    "POST",
                    JSONObject().put("writes", JSONArray().put(write)),
                    idToken,
                )
                return@runCatching code
            } catch (e: FirebaseApiException) {
                if (!e.code.contains("ALREADY_EXISTS", ignoreCase = true) &&
                    !e.code.contains("already exists", ignoreCase = true)
                ) {
                    throw e
                }
                lastConflict = e
            }
        }
        throw IllegalStateException("提取码生成失败，请稍后重试", lastConflict)
    }

    /**
     * 从云端拉取 6 位提取码对应的课表数据（需已登录）。
     */
    suspend fun pullShareFromCloud(
        code: String,
        idToken: String,
    ): Result<Pair<PartnerProfile, List<Course>>> = runCatching {
        val cleanCode = normalizeShareCode(code)
        val doc = try {
            gateway.firestore(sharePath(cleanCode), "GET", null, idToken)
        } catch (e: FirebaseApiException) {
            if (e.code.contains("NOT_FOUND", ignoreCase = true) ||
                e.code.contains("not found", ignoreCase = true)
            ) {
                throw IllegalArgumentException("提取码不存在或已失效，请核对后重试")
            }
            throw e
        }

        val fields = doc.optJSONObject("fields")
            ?: throw IllegalArgumentException("提取码对应的课表数据已失效")
        val payload = FirestoreFields.getString(fields, "payload")
        if (payload.isBlank()) throw IllegalArgumentException("提取码对应的课表数据为空")
        parseSharePayload(payload).getOrThrow()
    }

    /**
     * 生成上传到云端的课表 JSON（不含口令包装）。
     *
     * @param obscureCourseDetails 若为 true，脱敏隐藏具体课程名和地点，只展示“有课”
     */
    fun createShareJson(
        ownerName: String,
        type: PartnerType,
        colorHue: Int,
        courses: List<Course>,
        obscureCourseDetails: Boolean = false,
    ): String {
        val root = JSONObject()
        root.put("v", 1)
        root.put("owner", ownerName.trim().ifEmpty { "我的课表" })
        root.put("type", type.name)
        root.put("hue", colorHue)
        root.put("time", System.currentTimeMillis())

        val arr = JSONArray()
        for (c in courses) {
            val obj = JSONObject()
            obj.put("id", c.id)
            obj.put("name", if (obscureCourseDetails) "忙碌(有课)" else c.name)
            obj.put("loc", if (obscureCourseDetails) "" else c.location)
            obj.put("tea", if (obscureCourseDetails) "" else c.teacher)
            obj.put("day", c.weekday)
            obj.put("days", c.weekdays)
            obj.put("sp", c.startPeriod)
            obj.put("ep", c.endPeriod)
            obj.put("wt", c.weekType.name)
            obj.put("ws", c.weekStart)
            obj.put("we", c.weekEnd)
            obj.put("weeks", WeekSpec.encode(c.weeks))
            c.customStartMinute?.let { obj.put("csm", it) }
            c.customEndMinute?.let { obj.put("cem", it) }
            obj.put("note", if (obscureCourseDetails) "" else c.note)
            arr.put(obj)
        }
        root.put("courses", arr)
        return root.toString()
    }

    /**
     * 从剪贴板或用户输入的口令中提取并解析课表数据。
     */
    fun parseSharePayload(rawText: String): Result<Pair<PartnerProfile, List<Course>>> = runCatching {
        val token = extractToken(rawText)
        if (token.isBlank()) {
            throw IllegalArgumentException("未检测到有效的课表口令内容")
        }
        val jsonStr = try {
            val decodedBytes = Base64.getMimeDecoder().decode(token)
            val decoded = String(decodedBytes, Charsets.UTF_8)
            if (decoded.trimStart().startsWith("{")) decoded else token
        } catch (_: Exception) {
            token // 若本身就是 JSON 字符串
        }

        val root = JSONObject(jsonStr)
        if (!root.has("courses") && !root.has("owner")) {
            throw IllegalArgumentException("口令格式无法识别，请确认复制了完整的余课课表口令")
        }
        val owner = root.optString("owner", "好友课表").trim().ifEmpty { "好友课表" }
        val typeStr = root.optString("type", PartnerType.COUPLE.name)
        val type = PartnerType.of(typeStr)
        val hue = root.optInt("hue", type.defaultHue).coerceIn(0, 359)
        val partnerId = "partner_${UUID.randomUUID().toString().take(8)}"

        val profile = PartnerProfile(
            id = partnerId,
            name = owner,
            type = type,
            colorHue = hue,
            isCurrent = true,
            updatedAt = System.currentTimeMillis(),
        )

        val arr = root.optJSONArray("courses") ?: JSONArray()
        val courses = ArrayList<Course>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val rawId = obj.optString("id", UUID.randomUUID().toString())
            val id = if (rawId.startsWith("${partnerId}_")) rawId else "${partnerId}_$rawId"
            val name = obj.optString("name", "未命名课程")
            val loc = obj.optString("loc", "")
            val tea = obj.optString("tea", "")
            val day = obj.optInt("day", 1).coerceIn(1, 7)
            val days = obj.optInt("days", 1 shl (day - 1)).let { if (it > 0) it else (1 shl (day - 1)) }
            val sp = obj.optInt("sp", 1)
            val ep = obj.optInt("ep", 1).coerceAtLeast(sp)
            val wt = enumOrDefault(obj.optString("wt"), WeekType.EVERY_WEEK)
            val ws = obj.optInt("ws", 1).coerceAtLeast(1)
            val we = obj.optInt("we", 16).coerceAtLeast(ws)
            val weeksStr = obj.optString("weeks", "")
            val csm = if (obj.has("csm") && !obj.isNull("csm")) obj.getInt("csm") else null
            val cem = if (obj.has("cem") && !obj.isNull("cem")) obj.getInt("cem") else null
            val note = obj.optString("note", "")

            courses.add(
                Course(
                    id = id,
                    name = name,
                    location = loc,
                    teacher = tea,
                    weekday = day,
                    weekdays = days,
                    startPeriod = sp,
                    endPeriod = ep,
                    weekType = wt,
                    weekStart = ws,
                    weekEnd = we,
                    weeks = WeekSpec.decode(weeksStr),
                    customStartMinute = csm,
                    customEndMinute = cem,
                    note = note,
                    semesterId = partnerId,
                    colorHue = hue,
                    isPartner = true,
                    ownerName = owner,
                ),
            )
        }

        profile to courses
    }

    private fun extractToken(text: String): String {
        val trimmed = text.trim()
        val match = REGEX_TOKEN.find(trimmed)
        if (match != null) {
            return match.groupValues[1]
        }
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1)
        }
        return trimmed
    }

    /**
     * 计算双方在指定星期的共同空闲时段（空闲交集雷达）。
     *
     * 规则：在白天标准作息范围（从第 1 节到最后一节）内，寻找双方都没有课程安排的连续节次。
     */
    fun calculateCommonFreeSlots(
        myCourses: List<Course>,
        partnerCourses: List<Course>,
        periods: List<Schedule.Period>,
        week: Int,
        weekday: Int,
        displayWeekday: Int = weekday,
    ): List<CommonFreeSlot> {
        if (periods.isEmpty()) return emptyList()

        val myDayCourses = myCourses.filter { it.isOnWeekday(weekday) && it.isActiveOnWeek(week) }
        val partnerDayCourses = partnerCourses.filter { it.isOnWeekday(weekday) && it.isActiveOnWeek(week) }

        val periodCount = periods.size
        val isFree = BooleanArray(periodCount) { true }

        for (pIdx in 0 until periodCount) {
            val pNum = pIdx + 1
            val period = periods[pIdx]

            // 检查我是否有课
            val myHasClass = myDayCourses.any { c ->
                if (c.hasCustomTime()) {
                    val s = c.customStartMinute ?: 0
                    val e = c.customEndMinute ?: 0
                    s < period.end && e > period.start
                } else {
                    c.overlapsPeriod(pNum, pNum)
                }
            }
            if (myHasClass) {
                isFree[pIdx] = false
                continue
            }

            // 检查伴侣是否有课
            val partnerHasClass = partnerDayCourses.any { c ->
                if (c.hasCustomTime()) {
                    val s = c.customStartMinute ?: 0
                    val e = c.customEndMinute ?: 0
                    s < period.end && e > period.start
                } else {
                    c.overlapsPeriod(pNum, pNum)
                }
            }
            if (partnerHasClass) {
                isFree[pIdx] = false
            }
        }

        // 合并连续空闲节次
        val slots = mutableListOf<CommonFreeSlot>()
        var startIdx: Int? = null

        for (i in 0 until periodCount) {
            if (isFree[i]) {
                if (startIdx == null) startIdx = i
            } else {
                if (startIdx != null) {
                    val sp = startIdx + 1
                    val ep = i
                    val sMin = periods[startIdx].start
                    val eMin = periods[i - 1].end
                    slots.add(CommonFreeSlot(displayWeekday, sp, ep, sMin, eMin))
                    startIdx = null
                }
            }
        }
        if (startIdx != null) {
            val sp = startIdx + 1
            val ep = periodCount
            val sMin = periods[startIdx].start
            val eMin = periods[periodCount - 1].end
            slots.add(CommonFreeSlot(displayWeekday, sp, ep, sMin, eMin))
        }

        return slots
    }

    /**
     * 计算整周（结合调休安排）的共同空闲时段列表。
     */
    fun calculateWeekCommonFreeSlots(
        myCourses: List<Course>,
        partnerCourses: List<Course>,
        periods: List<Schedule.Period>,
        week: Int,
        teachingDays: List<Pair<Int, Int>?> = (1..7).map { week to it },
        visibleDays: List<Int> = (1..7).toList(),
        minPeriods: Int = 2,
    ): List<CommonFreeSlot> {
        if (periods.isEmpty()) return emptyList()
        val result = mutableListOf<CommonFreeSlot>()
        for (day in visibleDays) {
            val teaching = teachingDays.getOrNull(day - 1) ?: continue
            val daySlots = calculateCommonFreeSlots(
                myCourses = myCourses,
                partnerCourses = partnerCourses,
                periods = periods,
                week = teaching.first,
                weekday = teaching.second,
                displayWeekday = day,
            )
            result.addAll(daySlots.filter { it.periodCount >= minPeriods })
        }
        return result
    }

    companion object {
        private val REGEX_TOKEN = "￥([A-Za-z0-9+/=\\s]+)￥".toRegex()
        private val SHARE_CODE_REGEX = Regex("^[A-Z0-9]{6}$")

        /** 提取码撞车时的重试次数（32^6 ≈ 10 亿，正常一次必中）。 */
        private const val CODE_ATTEMPTS = 5

        /** 去掉易混淆的 0 / O / 1 / I。 */
        private const val CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

        /** 规整用户输入的提取码；格式不合法时抛出可读异常。 */
        fun normalizeShareCode(raw: String): String {
            val clean = raw.trim().uppercase().filter { it in '0'..'9' || it in 'A'..'Z' }
            require(SHARE_CODE_REGEX.matches(clean)) { "请输入 6 位提取码（字母或数字）" }
            return clean
        }

        private fun randomShareCode(): String =
            (1..6).map { CODE_ALPHABET.random() }.joinToString("")
    }
}

package com.kxin.classtable.domain.model

enum class PartnerType(val label: String, val defaultHue: Int) {
    COUPLE("情侣", 345),  // 紅梅 / 绛紫 (OKLCh 色相角)
    FRIEND("好友", 165),  // 若竹 / 浅葱
    ;

    companion object {
        fun of(name: String?): PartnerType = entries.firstOrNull { it.name == name } ?: FRIEND
    }
}

/**
 * 双人课表可选的专属和色色相（OKLCh 色相角）。
 */
data class PartnerHueOption(
    val label: String,
    val hue: Int,
)

val PartnerHuePalette = listOf(
    PartnerHueOption("紅梅", 345),
    PartnerHueOption("桜", 5),
    PartnerHueOption("珊瑚", 25),
    PartnerHueOption("山吹", 75),
    PartnerHueOption("若竹", 165),
    PartnerHueOption("浅葱", 195),
    PartnerHueOption("縹", 245),
    PartnerHueOption("藤", 295),
)

/**
 * 关注的朋友或伴侣档案。
 */
data class PartnerProfile(
    val id: String,
    val name: String,
    val type: PartnerType = PartnerType.COUPLE,
    val colorHue: Int = type.defaultHue,
    val avatarUrl: String = "",
    val shareCode: String = "",
    val isCurrent: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 双方共同空闲的时段（空闲交集雷达）。
 */
data class CommonFreeSlot(
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val startMinute: Int,
    val endMinute: Int,
) {
    val durationMinutes: Int get() = (endMinute - startMinute).coerceAtLeast(0)
    val periodCount: Int get() = (endPeriod - startPeriod + 1).coerceAtLeast(1)
}

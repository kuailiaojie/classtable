package com.kxin.classtable.icon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import com.kxin.classtable.R

/**
 * 可选的桌面图标:9 张「余白」角色图。
 *
 * 桌面读的是 **activity-alias** 的 icon,所以「换图标」= 换启用的那个 alias,而不是改
 * `application` 的图标(那个改不了)。每个 alias 都指向 MainActivity、共用同一个名字,
 * 同一时刻只启用一个 —— 否则桌面上会出现多个同名入口。
 *
 * ordinal 就是存进设置的 `appIconIndex`。
 */
enum class AppIcon(
    val label: String,
    @DrawableRes val drawableRes: Int,
    val alias: String,
) {
    LOOK_LEFT("向左看", R.drawable.app_icon_01, "com.kxin.classtable.IconAlias01"),
    FACE_FRONT("正面", R.drawable.app_icon_02, "com.kxin.classtable.IconAlias02"),
    SLEEPING("睡觉", R.drawable.app_icon_03, "com.kxin.classtable.IconAlias03"),
    LOOK_RIGHT("向右看", R.drawable.app_icon_04, "com.kxin.classtable.IconAlias04"),
    READING("看书", R.drawable.app_icon_05, "com.kxin.classtable.IconAlias05"),
    SURPRISED("惊讶", R.drawable.app_icon_06, "com.kxin.classtable.IconAlias06"),
    TEA("喝茶", R.drawable.app_icon_07, "com.kxin.classtable.IconAlias07"),
    WINKING("眨眼", R.drawable.app_icon_08, "com.kxin.classtable.IconAlias08"),
    BACK("背影", R.drawable.app_icon_09, "com.kxin.classtable.IconAlias09"),
    ;

    companion object {
        /** 出厂默认:正面(与 manifest 里 `enabled="true"` 的那个 alias 一致)。 */
        val DEFAULT = FACE_FRONT

        fun of(index: Int): AppIcon = entries.getOrElse(index) { DEFAULT }

        /** 轮播的下一个。 */
        fun nextIndex(index: Int): Int = (of(index).ordinal + 1) % entries.size
    }
}

/**
 * 把桌面图标切到 [icon]:**先启用目标,再关掉其余**。
 *
 * 顺序不能反 —— 先关后开会出现「一个入口都没有」的瞬间,桌面可能把图标删掉再重加。
 */
fun applyAppIcon(context: Context, icon: AppIcon) {
    val pm = context.packageManager
    setAliasEnabled(pm, context, icon.alias, enabled = true)
    AppIcon.entries.forEach { other ->
        if (other != icon) setAliasEnabled(pm, context, other.alias, enabled = false)
    }
}

private fun setAliasEnabled(pm: PackageManager, context: Context, alias: String, enabled: Boolean) {
    val target = if (enabled) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
    val component = ComponentName(context.packageName, alias)
    // 已是目标状态就不要再设:setComponentEnabledSetting 会让桌面重新拉一遍图标,
    // 每次启动都无脑设置,图标会无谓地闪一下。
    if (pm.getComponentEnabledSetting(component) == target) return
    pm.setComponentEnabledSetting(component, target, PackageManager.DONT_KILL_APP)
}

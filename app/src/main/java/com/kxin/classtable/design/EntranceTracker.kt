package com.kxin.classtable.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * 一次性入场守卫。
 *
 * 课表用 `HorizontalPager(beyondViewportPageCount = 1)`:翻走的页会被回收,回头**重新组合**,
 * 于是 `Animatable` 重新从 0 播一遍 —— 每翻一次就重新演一次入场,廉价且打断人(任务连续)。
 *
 * 这份守卫挂在**屏幕**上(而非页内),同一个 [key] 在本次屏幕存活期内只允许播一次;
 * 已经在别的页出现过的课程,再回来就是直接呈现,不再重演。
 */
class EntranceTracker {
    private val seen = mutableSetOf<Any>()

    fun shouldAnimate(key: Any): Boolean = key !in seen

    fun markSeen(key: Any) {
        seen += key
    }
}

@Composable
fun rememberEntranceTracker(): EntranceTracker = remember { EntranceTracker() }

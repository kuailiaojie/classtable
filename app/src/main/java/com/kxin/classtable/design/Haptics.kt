package com.kxin.classtable.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * 触感反馈的唯一入口。
 *
 * 触感是「反馈明确」里最直接的一层:动作已经被接收,不必等视觉变化才确认。但它也是最能
 * 变成噪音的一层 —— 所以只分两档,且只在**状态确凿改变**处调用:
 *
 * - [tick] —— 轻的一下。选日期、切页签、拨开关这类「换了一个值」。
 * - [select] —— 实一点的一下。进入 / 退出多选、勾选单行、破坏性操作确认这类「改变了一件东西」。
 *
 * 不要在滚动、翻页、入场动画里给触感 —— 那不是用户的动作,反馈只会让人以为手机在震。
 */
class YohakuHaptics(private val feedback: HapticFeedback) {
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    fun select() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)
}

@Composable
fun rememberYohakuHaptics(): YohakuHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { YohakuHaptics(feedback) }
}

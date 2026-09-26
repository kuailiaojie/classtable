package com.kxin.classtable.design

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut

/**
 * 动效 token。
 *
 * 取自 GSAP 的运动原则(不是它的 API —— 那是 JS 库,在 Compose 里跑不了):
 * - **缓动统一**:GSAP 的 `power2.out` / `power2.inOut` / `expo.out` / `back.out` 就是
 *   三次贝塞尔曲线,这里逐一对应成 [CubicBezierEasing];同类元素用同一条曲线,动作才像一套。
 * - **时长克制**:多数交互落在 180–460ms(GSAP 的惯例),只有开屏这类「编排」才用更长的时间线。
 * - **错峰 = stagger**:同类元素依次入场,用 [staggerDelay] 把序号变成延迟,而不是串一堆 delay。
 * - **只动合成层**:动画一律作用在 alpha / translation / scale 上,不碰会触发布局的属性。
 */
object YohakuMotion {
    /** 按压反馈、选中态这类即时响应。 */
    const val durFast = 180

    /** 通用入场 / 状态切换。 */
    const val durBase = 280

    /** 页面转场、面板滑入。 */
    const val durSlow = 460

    /** 开屏时间线。 */
    const val durXSlow = 700

    /** 错峰步长:列表项、课程块依次入场的间隔。 */
    const val stagger = 40

    /** GSAP `power2.out` ≈ easeOutCubic。 */
    val easeOut: Easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

    /** GSAP `power2.inOut` ≈ easeInOutCubic。 */
    val easeInOut: Easing = CubicBezierEasing(0.645f, 0.045f, 0.355f, 1f)

    /** GSAP `expo.out`:开头很快、结尾极缓,用于强调性入场。 */
    val easeExpoOut: Easing = CubicBezierEasing(0.19f, 1f, 0.22f, 1f)

    /** GSAP `back.out`:轻微过冲再回位,用于弹窗 / 面板这种「出现」的动作。 */
    val easeBackOut: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

    fun <T> tween(
        durationMs: Int = durBase,
        easing: Easing = easeOut,
        delayMs: Int = 0,
    ): TweenSpec<T> = TweenSpec(durationMs, delayMs, easing)

    /** 温和回弹:布局位置、尺寸这类变化。 */
    fun <T> gentleSpring(): SpringSpec<T> =
        SpringSpec(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

    /** 干脆利落:选中指示器、位移。 */
    fun <T> snappySpring(): SpringSpec<T> =
        SpringSpec(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)

    /** 明显回弹:手势释放、开关。 */
    fun <T> bouncySpring(): SpringSpec<T> =
        SpringSpec(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)

    /** 第 [index] 项的入场延迟(错峰)。 */
    fun staggerDelay(index: Int, stepMs: Int = stagger): Int = index * stepMs
}

/** 淡入 + 轻微放大:弹窗、卡片、面板的通用入场。 */
fun fadeScaleIn(scale: Float = 0.96f, durationMs: Int = YohakuMotion.durBase): EnterTransition =
    fadeIn(animationSpec = YohakuMotion.tween<Float>(durationMs)) +
        scaleIn(
            animationSpec = YohakuMotion.tween<Float>(YohakuMotion.durSlow, YohakuMotion.easeExpoOut),
            initialScale = scale,
        )

/** 淡出 + 轻微缩放:与 [fadeScaleIn] 对应的出场。 */
fun fadeScaleOut(scale: Float = 0.98f, durationMs: Int = YohakuMotion.durFast): ExitTransition =
    fadeOut(animationSpec = YohakuMotion.tween<Float>(durationMs)) +
        scaleOut(
            animationSpec = YohakuMotion.tween<Float>(YohakuMotion.durBase, YohakuMotion.easeInOut),
            targetScale = scale,
        )

package com.kxin.classtable.design

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut

/**
 * 动效 token —— 全应用动画的**唯一数值来源**。
 *
 * 取自 GSAP 的运动原则(不是它的 API —— 那是 JS 库,在 Compose 里跑不了),
 * 对应关系见 [YohakuTimeline] 与本文件各成员:
 *
 * | GSAP | 这里 |
 * | --- | --- |
 * | `gsap.timeline()` + position 参数 / `stagger` | [YohakuTimeline] |
 * | `power2.out` / `power2.inOut` / `expo.out` / `back.out` | [easeOut] / [easeInOut] / [easeExpoOut] / [easeBackOut] |
 * | `ease: "none"` | [easeLinear] |
 * | duration(秒) | [durFast] / [durBase] / [durSlow] / [durXSlow] |
 * | `stagger: { each }` | [staggerDelay] 与 [YohakuTimeline.stagger] |
 * | `prefers-reduced-motion` + `gsap.matchMedia()` | Compose 的 `MotionDurationScale`([YohakuTimeline.play] 已对齐) |
 *
 * 三条铁律:
 * - **缓动统一**:同类元素用同一条曲线,动作才像一套;
 * - **时长克制**:交互落在 180–460ms,只有开屏这类「编排」才用更长的时间线;
 * - **只动合成层**:动画一律作用在 alpha / translation / scale 上,不碰会触发布局的属性。
 */
object YohakuMotion {
    /** 按压反馈、选中态这类即时响应。 */
    const val durFast = 180

    /** 通用入场 / 状态切换。 */
    const val durBase = 280

    /** 页面转场、面板滑入。 */
    const val durSlow = 460

    /** 开屏时间线(品牌条生长)。 */
    const val durXSlow = 700

    /**
     * 「正在上」呼吸的一次往返时长(半程 = 这个值 / 2)。
     *
     * 比常规循环更慢:它是**持续状态**的呼吸,不是一次动作;太快会变成闪烁,变成干扰。
     */
    const val durPulse = 1400

    /** 错峰步长:列表项、课程块依次入场的间隔。 */
    const val stagger = 40

    /** 紧凑错峰:一屏内塞得下的网格(周视图课程块)。 */
    const val staggerTight = 22

    /** 疏松错峰:条目少、需要被逐一看清时(权限引导)。 */
    const val staggerLoose = 60

    /**
     * 周视图表头随翻页的视差量(px)。
     *
     * 只用于 [graphicsLayer] 的 translationX(单位是像素,不是 dp),别照搬去写 dp。
     */
    const val headerParallaxPx = 56f

    /** GSAP `ease: "none"` —— 匀速,用于进度驱动(品牌条生长、扫光)。 */
    val easeLinear: Easing = LinearEasing

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

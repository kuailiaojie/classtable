package com.kxin.classtable.design

import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * GSAP 式时间线:把若干步骤按**位置参数**排在一条时间轴上,一起推进。
 *
 * 对应 GSAP 的 `gsap.timeline()` + position 参数:与其把动画串成一堆 `delay()`,
 * 不如声明「这一步多长、从哪开始」。位置语法与 GSAP 一致:
 *
 * ```
 * motionTimeline {
 *     // 绝对起点(ms)
 *     step(YohakuMotion.durXSlow, "0")   { d -> reveal.animateTo(1f, YohakuMotion.tween(d, YohakuMotion.easeLinear)) }
 *     // 上一步结束后再等 160ms("+=160");"-=160" 则是提前
 *     step(YohakuMotion.durSlow, "+=160") { d -> sheen.animateTo(1f, YohakuMotion.tween(d, YohakuMotion.easeInOut)) }
 *     // "<" = 与上一步同时开始;"<200" = 比上一步晚 200ms 开始
 *     step(YohakuMotion.durSlow, "<")     { d -> title.animateTo(1f, YohakuMotion.tween(d, YohakuMotion.easeExpoOut)) }
 *     // 缺省 ">" = 接在上一步之后
 *     step(YohakuMotion.durSlow)          { d -> tagline.animateTo(1f, YohakuMotion.tween(d, YohakuMotion.easeExpoOut)) }
 *     // 错峰:7 步依次错开 60ms(GSAP 的 stagger)
 *     stagger(count = 7, stepMs = YohakuMotion.staggerLoose, durationMs = YohakuMotion.durSlow) { i, d ->
 *         progresses[i].animateTo(1f, YohakuMotion.tween(d, YohakuMotion.easeExpoOut))
 *     }
 * }.play()   // 或直接 motionTimeline { ... }
 * ```
 *
 * 减动效:系统「动画缩放」(移除动画 = 0)会被 [play] 读到,并**按同一系数压缩各步的起点偏移**。
 * Compose 的 `animate*` 自己也会按这个系数缩放动画时长,所以缩放后各步的相对时序仍然对齐 ——
 * 这正是 GSAP `gsap.matchMedia()` 在 `prefers-reduced-motion` 下的等价物。
 */
class YohakuTimeline {

    private class PlannedStep(
        val startMs: Int,
        val durationMs: Int,
        val block: suspend (durationMs: Int) -> Unit,
    )

    private val steps = mutableListOf<PlannedStep>()

    /** 上一步的起点 / 终点(设计时长,未乘系统缩放)。 */
    private var previousStart = 0
    private var previousEnd = 0

    /**
     * 追加一步动画。
     *
     * @param durationMs 这一步的时长(交给 [YohakuMotion.tween];无需自己乘系统缩放)。
     * @param position 起点,语法同 GSAP:`"0"` / `"1200"` 绝对毫秒,`"+="` / `"-="` 相对上一步终点,
     *   `"<"` / `">"` 上一步起点 / 终点,`"<n"` / `">n"` 再偏移 n 毫秒。缺省 `">"`。
     * @param block 到点后执行的动画,入参是本步时长。
     */
    fun step(
        durationMs: Int,
        position: String = ">",
        block: suspend (durationMs: Int) -> Unit,
    ) {
        val start = resolve(position)
        steps += PlannedStep(start, durationMs, block)
        previousStart = start
        previousEnd = start + durationMs
    }

    /**
     * 错峰追加 [count] 步:第 i 步的起点是 [position] 再往后 i×[stepMs](GSAP 的 stagger)。
     *
     * @param block 入参是序号与时长。
     */
    fun stagger(
        count: Int,
        stepMs: Int,
        durationMs: Int = YohakuMotion.durSlow,
        position: String = ">",
        block: suspend (index: Int, durationMs: Int) -> Unit,
    ) {
        if (count <= 0) return
        val base = resolve(position)
        repeat(count) { index ->
            steps += PlannedStep(base + index * stepMs, durationMs) { d -> block(index, d) }
        }
        previousStart = base
        previousEnd = base + (count - 1) * stepMs + durationMs
    }

    private fun resolve(position: String): Int {
        val spec = position.trim()
        return when {
            spec == "<" -> previousStart
            spec == ">" -> previousEnd
            spec.startsWith("+=") -> previousEnd + spec.drop(2).trim().toInt()
            spec.startsWith("-=") -> previousEnd - spec.drop(2).trim().toInt()
            spec.startsWith("<") -> previousStart + spec.drop(1).trim().toInt()
            spec.startsWith(">") -> previousEnd + spec.drop(1).trim().toInt()
            else -> spec.toInt()
        }
    }

    suspend fun play() {
        if (steps.isEmpty()) return
        // delay 不受 Compose 的动画缩放管辖(animate* 受),所以这里只压缩「起点偏移」,
        // 时长原样交给 animate* —— 两边同系数,缩放后相对时序才不会散。
        val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        coroutineScope {
            steps.forEach { planned ->
                launch {
                    val wait = (planned.startMs * scale).roundToInt()
                    if (wait > 0) delay(wait.toLong())
                    planned.block(planned.durationMs)
                }
            }
        }
    }
}

/** 建一条时间线并播放 —— `LaunchedEffect` 里的便捷入口。 */
suspend fun motionTimeline(block: YohakuTimeline.() -> Unit) {
    YohakuTimeline().apply(block).play()
}

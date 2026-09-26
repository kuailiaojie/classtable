package com.kxin.classtable.design

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/**
 * 按压反馈:按下时整块淡淡压深一层,没有 Material 涟漪那种从触点扩散的圆。
 *
 * 反馈本身保留(Interaction 的「按下」状态不能省),只是换成纸面感的表达:
 * 纸被按压时颜色加深,而不是浮起一圈水波。
 *
 * 压深与回弹都带缓动(以前是瞬时切换):按下 180ms 内淡入,松开慢慢淡出 ——
 * 反馈只在合成层重绘,不触发布局。
 */
class YohakuIndication(private val overlay: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        YohakuIndicationNode(interactionSource, overlay)

    override fun equals(other: Any?): Boolean = other is YohakuIndication && other.overlay == overlay
    override fun hashCode(): Int = overlay.hashCode()
}

private class YohakuIndicationNode(
    private val source: InteractionSource,
    private val overlay: Color,
) : Modifier.Node(), DrawModifierNode {
    private val alpha = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press ->
                        alpha.animateTo(PRESS_ALPHA, YohakuMotion.tween(YohakuMotion.durFast))
                    is PressInteraction.Release ->
                        alpha.animateTo(0f, YohakuMotion.tween(YohakuMotion.durSlow))
                    is PressInteraction.Cancel ->
                        alpha.animateTo(0f, YohakuMotion.tween(YohakuMotion.durBase))
                    else -> Unit
                }
            }
        }
        // Animatable 的值变化不会自动触发重绘,显式观察并 invalidate
        coroutineScope.launch {
            snapshotFlow { alpha.value }.collect { invalidateDraw() }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val a = alpha.value
        if (a > 0.001f) drawRect(color = overlay, alpha = a)
    }

    private companion object {
        const val PRESS_ALPHA = 0.07f
    }
}

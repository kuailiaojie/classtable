package com.kxin.classtable.design

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                val nowPressed = interaction is PressInteraction.Press
                if (nowPressed != pressed) {
                    pressed = nowPressed
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressed) {
            drawRect(color = overlay, alpha = PRESS_ALPHA)
        }
    }

    private companion object {
        const val PRESS_ALPHA = 0.07f
    }
}

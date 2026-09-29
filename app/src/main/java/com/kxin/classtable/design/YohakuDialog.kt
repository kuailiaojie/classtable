package com.kxin.classtable.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * 纸面弹窗:与卡片同一套纪律(浮起面 + 细边框 + radiusSheet 圆角),**不继承任何
 * Material 默认值** —— 那一套是 surfaceContainerHigh 紫调底、28dp 圆角、24sp 标题、6dp tonal 提升。
 *
 * 浮起面比纸面更白(深色下更亮),弹窗在遮罩之上自然是「最靠前」的一层;
 * 早先用 neutral2 灰底,浅色下弹窗反而是全屏最暗的一块。
 *
 * 按钮沿用项目风格:右下角一排纯文字动作(见 [YohakuDialogAction])。
 */
@Composable
fun YohakuDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalYohakuColors.current
    Dialog(onDismissRequest = onDismissRequest) {
        // 入场:轻微过冲 + 淡入(弹窗这种「出现」的动作,取 GSAP 的 back.out;退出交给系统窗口)
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        val progress by animateFloatAsState(
            targetValue = if (shown) 1f else 0f,
            animationSpec = YohakuMotion.tween(YohakuMotion.durSlow, YohakuMotion.easeBackOut),
            label = "dialogIn",
        )
        val shape = RoundedCornerShape(YohakuDimens.radiusSheet)
        Box(
            modifier = modifier
                .fillMaxWidth()
                .graphicsLayer {
                    // back.out 会略微冲过 1,alpha 得夹住;scale 的超调正好就是那点回弹
                    alpha = progress.coerceIn(0f, 1f)
                    val s = 0.94f + 0.06f * progress
                    scaleX = s
                    scaleY = s
                }
                .clip(shape)
                .background(colors.raised)
                .border(1.dp, colors.line, shape)
                .padding(horizontal = YohakuDimens.screenPadding, vertical = 16.dp),
        ) {
            Column {
                if (!title.isNullOrBlank()) {
                    Text(text = title, style = YohakuType.title20, color = colors.neutral10)
                    Spacer(modifier = Modifier.height(10.dp))
                }
                content()
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

/** 弹窗动作:纯文字按钮。[accent] = 主操作(全屏唯一 accent 纪律);[destructive] = 删除这类不可逆动作。 */
@Composable
fun YohakuDialogAction(
    text: String,
    onClick: () -> Unit,
    accent: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalYohakuColors.current
    Text(
        text = text,
        style = YohakuType.copy14,
        color = when {
            !enabled -> colors.neutral5
            destructive -> colors.error
            accent -> colors.accent
            else -> colors.neutral7
        },
        modifier = Modifier
            .defaultMinSize(minHeight = 44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
    )
}

/**
 * 破坏性操作的二次确认:默认「取消 / 删除」。
 *
 * 课程、日程、调休、批量删除此前都是**点一下就直接生效** —— 删除不可逆,而动作本身
 * 只是一个行尾小字,误触的代价太大。统一走这里:把后果写进 [message],确认动作染 error 色。
 */
@Composable
fun YohakuConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "删除",
    dismissText: String = "取消",
    destructive: Boolean = true,
) {
    val colors = LocalYohakuColors.current
    YohakuDialog(
        onDismissRequest = onDismiss,
        title = title,
        actions = {
            YohakuDialogAction(text = dismissText, onClick = onDismiss)
            YohakuDialogAction(
                text = confirmText,
                accent = !destructive,
                destructive = destructive,
                onClick = onConfirm,
            )
        },
    ) {
        Text(text = message, style = YohakuType.copy14, color = colors.neutral9)
    }
}

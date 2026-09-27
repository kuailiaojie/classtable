package com.kxin.classtable.ui.permissions

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuMotion
import com.kxin.classtable.design.YohakuType

/**
 * 权限/可靠性行:标题 + 说明,右侧状态(已开启/未开启/建议开启)。
 * [ok] = true 已开启(accent)/ false 未开启 / null 无法自动检测(建议开启)。
 * [onClick] 非空时整行可点,跳转对应系统设置页。
 *
 * 状态从「未开启」翻到「已开启」时,圆点放大、文字换色都带过渡 —— 授权返回后能看见变化,
 * 而不是一帧之内换掉。
 */
@Composable
fun PermissionRow(
    title: String,
    ok: Boolean?,
    hint: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    val statusColor by animateColorAsState(
        targetValue = if (ok == true) colors.accent else colors.neutral7,
        animationSpec = YohakuMotion.tween(YohakuMotion.durBase),
        label = "permissionStatus",
    )
    val dotScale by animateFloatAsState(
        targetValue = if (ok == true) 1f else 0.0001f,
        animationSpec = YohakuMotion.snappySpring(),
        label = "permissionDot",
    )
    Row(
        modifier = modifier
            .padding(horizontal = 20.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 0.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = YohakuType.copy15, color = colors.neutral9)
            Text(text = hint, style = YohakuType.label12, color = colors.neutral6)
        }
        Box(
            modifier = Modifier
                .size(6.dp)
                .graphicsLayer {
                    scaleX = dotScale
                    scaleY = dotScale
                }
                .clip(CircleShape)
                .background(colors.accent),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = when (ok) {
                true -> "已开启"
                false -> "未开启"
                null -> "建议开启"
            },
            style = YohakuType.label12,
            color = statusColor,
        )
        if (onClick != null) {
            Text(
                text = "›",
                style = YohakuType.copy15,
                color = colors.neutral6,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

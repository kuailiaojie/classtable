package com.kxin.classtable.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 自绘开关。选中 = accent 实底 + 白色滑块;滑块位移与轨道颜色都带缓动。
 *
 * 与 chip 同一套纪律:选中态用 accent 表达(全屏唯一强调色),不做 Material 那种
 * 带涟漪与 tonal 阴影的 Switch。
 */
@Composable
fun YohakuSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    val shape = RoundedCornerShape(percent = 50)
    val track by animateColorAsState(
        targetValue = if (checked) colors.accent else colors.neutral3,
        animationSpec = YohakuMotion.tween(YohakuMotion.durBase),
        label = "switchTrack",
    )
    val thumbX by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = YohakuMotion.snappySpring(),
        label = "switchThumb",
    )
    Box(
        modifier = modifier
            .size(width = 46.dp, height = 28.dp)
            .clip(shape)
            .background(track)
            .border(1.dp, if (checked) colors.accent else colors.line, shape)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbX)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (checked) Color.White else colors.raised),
        )
    }
}

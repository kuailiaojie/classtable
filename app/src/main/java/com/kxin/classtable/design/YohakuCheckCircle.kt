package com.kxin.classtable.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 遵循 Apple HIG 44×44pt 最小触控靶心的极简复选圆圈打卡组件。
 *
 * 视觉上保持 20dp（或自定义尺寸）的克制和纸圆圈，外层通过 [yohakuTouchTarget] 铺满至少 44×44dp 的触控热区，
 * 彻底杜绝在卡片或列表中“想打卡却误触跳转详情页”的移动端常见痛点。
 */
@Composable
fun YohakuCheckCircle(
    completed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    visualSize: Dp = 20.dp,
) {
    val colors = LocalYohakuColors.current
    val haptics = rememberYohakuHaptics()

    Box(
        modifier = modifier
            .yohakuTouchTarget(minWidth = 40.dp, minHeight = 44.dp)
            .semantics { role = Role.Checkbox }
            .clickable {
                haptics.select()
                onToggle()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(visualSize)
                .clip(CircleShape)
                .background(if (completed) colors.accent else colors.paper)
                .border(
                    BorderStroke(1.5.dp, if (completed) colors.accent else colors.neutral5),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (completed) {
                Text(
                    text = "✓",
                    style = YohakuType.label12,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

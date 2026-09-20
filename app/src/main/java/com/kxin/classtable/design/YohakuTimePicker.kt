package com.kxin.classtable.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 纸面时间选择器:自绘「时 / 分」步进 + 精确输入,不用 Material `TimePicker`
 * (那一套自带表盘、Material 字阶与涟漪,和 Yohaku 是两套语言)。
 *
 * 为什么做成选择器而不是纯文本框:一屏 12 节 × 2 个时间,逐格手敲 "08:05" 既慢又容易敲错
 * (冒号/前导零/全角),而这些错误只能靠保存前的校验兜住。改成「点时间 → 步进微调」之后,
 * 常见操作变成点几下,输入框只留给非 5 分钟倍数的精确值。
 *
 * 步进粒度:时 = 1 小时,分 = 5 分钟(作息时间够用);另给 ±45 分钟(一节课的长度)的快捷。
 *
 * @param initial "HH:MM";解析失败时按 08:00 起算
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YohakuTimePicker(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    var text by remember { mutableStateOf(normalizeClock(initial) ?: "08:00") }
    val minutes = parseClock(text)

    fun shift(delta: Int) {
        val cur = parseClock(text) ?: return
        text = clockText((cur + delta).coerceIn(0, 23 * 60 + 59))
    }

    YohakuDialog(
        onDismissRequest = onDismiss,
        title = title,
        actions = {
            YohakuDialogAction(text = "取消", onClick = onDismiss)
            YohakuDialogAction(
                text = "确定",
                accent = true,
                enabled = minutes != null,
                onClick = { minutes?.let { onConfirm(clockText(it)) } },
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 大字预览:所见即所得,步进时数字原地跳,不用去两列小字里对
            Text(
                text = minutes?.let(::clockText) ?: "--:--",
                style = YohakuType.title28,
                color = if (minutes != null) colors.neutral10 else colors.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(14.dp))

            StepperRow(
                label = "时",
                value = minutes?.let { "%02d".format(it / 60) } ?: "--",
                onMinus = { shift(-60) },
                onPlus = { shift(60) },
            )
            Spacer(modifier = Modifier.height(8.dp))
            StepperRow(
                label = "分",
                value = minutes?.let { "%02d".format(it % 60) } ?: "--",
                onMinus = { shift(-5) },
                onPlus = { shift(5) },
            )

            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(-45 to "−45 分", -5 to "−5 分", 5 to "＋5 分", 45 to "＋45 分").forEach { (d, label) ->
                    QuickAdjust(
                        text = label,
                        enabled = minutes != null,
                        onClick = { shift(d) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            YohakuTextField(
                value = text,
                onValueChange = { text = it },
                label = "精确输入",
                placeholder = "08:00",
                isError = minutes == null,
            )
            if (minutes == null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "请按 24 小时制填写,如 08:00 / 14:30",
                    style = YohakuType.label12,
                    color = colors.error,
                )
            }
        }
    }
}

/** 一行步进:「标签 + − + 值 + ＋」,值居中定宽,连续点时数字不会左右跳。 */
@Composable
private fun StepperRow(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = YohakuType.copy13,
            color = colors.neutral7,
            modifier = Modifier.width(24.dp),
        )
        StepperKey(glyph = "−", onClick = onMinus)
        Text(
            text = value,
            style = YohakuType.title20,
            color = colors.neutral10,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        StepperKey(glyph = "＋", onClick = onPlus)
    }
}

@Composable
private fun StepperKey(glyph: String, onClick: () -> Unit) {
    val colors = LocalYohakuColors.current
    val shape = RoundedCornerShape(YohakuDimens.radiusControl)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(shape)
            .background(colors.neutral2)
            .border(1.dp, colors.line, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, style = YohakuType.title20, color = colors.neutral9)
    }
}

@Composable
private fun QuickAdjust(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalYohakuColors.current
    val shape = RoundedCornerShape(YohakuDimens.radiusChip)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(colors.neutral2)
            .border(1.dp, colors.line, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = YohakuType.label12,
            color = if (enabled) colors.neutral7 else colors.neutral5,
        )
    }
}

/** "8:5" / "08:05" / "08:05:00" → 分钟;非法返回 null。 */
private fun parseClock(s: String): Int? {
    val parts = s.trim().split(":")
    if (parts.size < 2) return null
    val h = parts[0].trim().toIntOrNull() ?: return null
    val m = parts[1].trim().toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

private fun clockText(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

/** 把 "8:5" 这类输入归一成 "08:05";非法返回 null。 */
private fun normalizeClock(s: String): String? = parseClock(s)?.let(::clockText)

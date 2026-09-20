package com.kxin.classtable.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth

/**
 * 纸面日期选择器:自绘月历,不用 Material `DatePicker`(那一套自带 Material 字阶、
 * 形状、颜色与涟漪,和 Yohaku 是两套语言)。
 *
 * 顶部一行四个动作:上年 / 上月 / 下月 / 次年;下面星期表头 + 6×7 日期格。
 * 今天用 accent 文字标出,选中用 accent 实底。
 *
 * @param initialEpochDay 当前值(epochDay);null = 未设置
 * @param onClear 未使用(由调用方在 actions 里处理「清除」)
 */
@Composable
fun YohakuDatePicker(
    initialEpochDay: Long?,
    onPick: (Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    val today = LocalDate.now()
    var cursor by remember {
        mutableStateOf(YearMonth.from(initialEpochDay?.let { LocalDate.ofEpochDay(it) } ?: today))
    }
    var selected by remember { mutableStateOf(initialEpochDay) }

    YohakuDialog(
        onDismissRequest = onDismiss,
        title = "选择日期",
        actions = {
            YohakuDialogAction(text = "清除", onClick = onClear)
            YohakuDialogAction(text = "取消", onClick = onDismiss)
            YohakuDialogAction(
                text = "确定",
                accent = true,
                enabled = selected != null,
                onClick = { selected?.let(onPick) },
            )
        },
    ) {
        // 年月切换:双箭头跨年,单箭头跨月
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Stepper("‹‹") { cursor = cursor.minusYears(1) }
            Stepper("‹") { cursor = cursor.minusMonths(1) }
            Text(
                text = "${cursor.year} 年 ${cursor.monthValue} 月",
                style = YohakuType.copy15,
                color = colors.neutral10,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )
            Stepper("›") { cursor = cursor.plusMonths(1) }
            Stepper("››") { cursor = cursor.plusYears(1) }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 星期表头(周一对齐,与周视图一致)
        Row(modifier = Modifier.fillMaxWidth()) {
            "一二三四五六日".forEach { c ->
                Text(
                    text = c.toString(),
                    style = YohakuType.gridDate,
                    color = colors.neutral6,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // 6×7 日期格
        val firstOfMonth = cursor.atDay(1)
        val leading = firstOfMonth.dayOfWeek.value - 1
        val daysInMonth = cursor.lengthOfMonth()
        var day = 1
        repeat(6) {
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { column ->
                    val cellIndex = it * 7 + column
                    val isEmpty = cellIndex < leading || day > daysInMonth
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!isEmpty) {
                            val date = cursor.atDay(day)
                            val epochDay = date.toEpochDay()
                            val isSelected = selected == epochDay
                            val isToday = date == today
                            val shape = RoundedCornerShape(YohakuDimens.radiusChip)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp)
                                    .clip(shape)
                                    .background(if (isSelected) colors.accent else Color.Transparent)
                                    .then(
                                        if (isToday && !isSelected) {
                                            Modifier.border(1.dp, colors.accent, shape)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .clickable { selected = epochDay },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = day.toString(),
                                    style = YohakuType.timeMono,
                                    color = when {
                                        isSelected -> Color.White
                                        isToday -> colors.accent
                                        else -> colors.neutral9
                                    },
                                )
                            }
                            day++
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stepper(glyph: String, onClick: () -> Unit) {
    val colors = LocalYohakuColors.current
    Text(
        text = glyph,
        style = YohakuType.copy15,
        color = colors.neutral7,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .width(36.dp)
            .clickable(onClick = onClick)
            .padding(6.dp),
    )
}

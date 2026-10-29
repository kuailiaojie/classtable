package com.kxin.classtable.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuDialog
import com.kxin.classtable.design.YohakuDialogAction
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.rememberYohakuHaptics
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.Course

/**
 * 周次快速选择面板:网格平铺展示全学期周次(1..总周数),
 * 展示每周日期区间、本周标记与课程数,支持 1-tap 快速跳周。
 */
@Composable
fun WeekPickerDialog(
    weekCount: Int,
    currentWeek: Int,
    selectedWeek: Int,
    semesterStartDay: Long,
    courses: List<Course>,
    onSelectWeek: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    val haptics = rememberYohakuHaptics()
    val totalWeeks = weekCount.coerceIn(1, 30)

    YohakuDialog(
        onDismissRequest = onDismiss,
        title = "选择周次",
        actions = {
            if (currentWeek in 1..totalWeeks && currentWeek != selectedWeek) {
                YohakuDialogAction(
                    text = "回到本周 (第${currentWeek}周)",
                    accent = true,
                    onClick = {
                        haptics.select()
                        onSelectWeek(currentWeek)
                    },
                )
            }
            YohakuDialogAction(text = "关闭", onClick = onDismiss)
        },
    ) {
        val subtitle = if (currentWeek in 1..totalWeeks) {
            "共 $totalWeeks 周 · 当前处于第 $currentWeek 周"
        } else {
            "共 $totalWeeks 周"
        }
        Text(
            text = subtitle,
            style = YohakuType.copy13,
            color = colors.neutral7,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp),
        ) {
            items((1..totalWeeks).toList()) { week ->
                val isSelected = week == selectedWeek
                val isCurrent = week == currentWeek
                val range = Schedule.weekRangeText(semesterStartDay, week)
                val activeCourseCount = courses.count { it.isActiveOnWeek(week) }

                val shape = RoundedCornerShape(YohakuDimens.radiusChip)
                val bg = when {
                    isSelected -> colors.neutral2
                    isCurrent -> colors.neutral1
                    else -> colors.paper
                }
                val borderColor = when {
                    isSelected -> colors.accent
                    isCurrent -> colors.neutral3
                    else -> colors.line
                }

                Box(
                    modifier = Modifier
                        .clip(shape)
                        .background(bg)
                        .border(if (isSelected) 1.5.dp else 1.dp, borderColor, shape)
                        .clickable {
                            haptics.select()
                            onSelectWeek(week)
                        }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "第$week 周",
                            style = YohakuType.copy13,
                            color = if (isSelected) colors.accent else if (isCurrent) colors.neutral10 else colors.neutral8,
                            fontWeight = if (isSelected || isCurrent) FontWeight.Medium else FontWeight.Normal,
                        )
                        if (range.isNotEmpty()) {
                            Text(
                                text = range,
                                style = YohakuType.gridGutterTime,
                                color = colors.neutral6,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        if (activeCourseCount > 0) {
                            Text(
                                text = "$activeCourseCount 节",
                                style = YohakuType.gridGutterTime,
                                color = if (isSelected) colors.accent else colors.neutral5,
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            Text(
                                text = "无课",
                                style = YohakuType.gridGutterTime,
                                color = colors.neutral5,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

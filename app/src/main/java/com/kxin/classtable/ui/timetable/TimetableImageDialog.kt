package com.kxin.classtable.ui.timetable

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kxin.classtable.data.TimetableImageExporter
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuDialog
import com.kxin.classtable.design.YohakuDialogAction
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuOutlineButton
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.rememberYohakuHaptics
import kotlinx.coroutines.launch

/**
 * 课表美学长图导出弹窗。
 *
 * 提供周次切换、长图实时预览、保存至系统相册与第三方应用长图分享。
 */
@Composable
fun TimetableImageDialog(
    initialWeek: Int,
    weekCount: Int,
    imageExporter: TimetableImageExporter,
    onDismiss: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberYohakuHaptics()

    var selectedWeek by remember { mutableIntStateOf(initialWeek.coerceIn(1, weekCount)) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isGenerating by remember { mutableStateOf(false) }

    LaunchedEffect(selectedWeek) {
        isGenerating = true
        currentBitmap = imageExporter.generateBitmap(selectedWeek)
        isGenerating = false
    }

    YohakuDialog(
        onDismissRequest = onDismiss,
        title = "导出课表长图",
        actions = {
            YohakuDialogAction(text = "关闭", onClick = onDismiss)
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 周次微调与指示
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(YohakuDimens.radiusChip))
                        .clickable(enabled = selectedWeek > 1) {
                            haptics.tick()
                            selectedWeek--
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "‹ 上一周",
                        style = YohakuType.copy14,
                        color = if (selectedWeek > 1) colors.accent else colors.neutral5,
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "第 $selectedWeek 周",
                    style = YohakuType.title20,
                    color = colors.neutral10,
                )
                Spacer(modifier = Modifier.width(16.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(YohakuDimens.radiusChip))
                        .clickable(enabled = selectedWeek < weekCount) {
                            haptics.tick()
                            selectedWeek++
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "下一周 ›",
                        style = YohakuType.copy14,
                        color = if (selectedWeek < weekCount) colors.accent else colors.neutral5,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 预览区域 (限高 380dp,内部可滚)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .clip(RoundedCornerShape(YohakuDimens.radiusControl))
                    .border(1.dp, colors.line, RoundedCornerShape(YohakuDimens.radiusControl))
                    .background(colors.raised),
                contentAlignment = Alignment.Center,
            ) {
                if (isGenerating || currentBitmap == null) {
                    Text(
                        text = "正在绘制超清艺术长图…",
                        style = YohakuType.copy14,
                        color = colors.neutral7,
                    )
                } else {
                    currentBitmap?.let { bmp ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "第 $selectedWeek 周课表预览",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "1800×3900 · 9:19.5 竖屏手机全面屏规格 · 可上下滑动预览",
                style = YohakuType.label11,
                color = colors.neutral6,
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 动作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                YohakuOutlineButton(
                    text = "保存到相册",
                    onClick = {
                        val bmp = currentBitmap ?: return@YohakuOutlineButton
                        scope.launch {
                            val res = imageExporter.saveToGallery(bmp, selectedWeek)
                            res.fold(
                                onSuccess = {
                                    Toast.makeText(context, "长图已保存至系统相册 (Pictures/余课)", Toast.LENGTH_SHORT).show()
                                },
                                onFailure = {
                                    Toast.makeText(context, "保存失败: ${it.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                    },
                    enabled = !isGenerating && currentBitmap != null,
                    modifier = Modifier.weight(1f),
                )

                YohakuButton(
                    text = "分享长图",
                    onClick = {
                        val bmp = currentBitmap ?: return@YohakuButton
                        scope.launch {
                            val res = imageExporter.prepareShareImage(bmp, selectedWeek)
                            res.fold(
                                onSuccess = { uri ->
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, "余课第${selectedWeek}周课表")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "分享课表长图"))
                                },
                                onFailure = {
                                    Toast.makeText(context, "分享失败: ${it.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                    },
                    enabled = !isGenerating && currentBitmap != null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

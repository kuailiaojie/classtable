package com.kxin.classtable.ui.timetable

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.kxin.classtable.data.IcsExporter
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuDialog
import com.kxin.classtable.design.YohakuDialogAction
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuOutlineButton
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.rememberYohakuHaptics
import kotlinx.coroutines.launch
import java.io.File

/**
 * 日历 (.ics) 导出弹窗。
 *
 * 将全学期课程与待办、考试导出为 RFC 5545 标准日历文件，
 * 支持一键导入系统日历（如 Apple Calendar、华为、小米等）或发送到 iPad、Mac 与手表。
 */
@Composable
fun IcsExportDialog(
    icsExporter: IcsExporter,
    onDismiss: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberYohakuHaptics()

    var isPreparing by remember { mutableStateOf(true) }
    var exportResult by remember { mutableStateOf<IcsExporter.IcsExportResult?>(null) }
    var shareFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            icsExporter.prepareShareFile()
        }.onSuccess { (file, result) ->
            shareFile = file
            exportResult = result
            isPreparing = false
        }.onFailure {
            isPreparing = false
            Toast.makeText(context, "日历生成失败: ${it.message}", Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }

    YohakuDialog(
        onDismissRequest = onDismiss,
        title = "导出系统日历 (.ics)",
        actions = {
            YohakuDialogAction(text = "取消", onClick = onDismiss)
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (isPreparing) {
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator(
                    color = colors.accent,
                    modifier = Modifier.padding(16.dp),
                )
                Text(
                    text = "正在编排全学期课程与日程…",
                    style = YohakuType.copy14,
                    color = colors.neutral7,
                )
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                exportResult?.let { res ->
                    Text(
                        text = "已生成符合 RFC 5545 标准的日历事件，支持直接导入系统日历或隔空投送至 Apple、穿戴手表等生态。",
                        style = YohakuType.copy14,
                        color = colors.neutral8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(YohakuDimens.gapTight))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "${res.totalCount}", style = YohakuType.title20, color = colors.accent)
                            Text(text = "总事件数", style = YohakuType.label12, color = colors.neutral7)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "${res.courseCount}", style = YohakuType.title20, color = colors.neutral10)
                            Text(text = "排课次数", style = YohakuType.label12, color = colors.neutral7)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "${res.agendaCount}", style = YohakuType.title20, color = colors.neutral10)
                            Text(text = "日程/目标", style = YohakuType.label12, color = colors.neutral7)
                        }
                    }

                    Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        YohakuOutlineButton(
                            text = "直接导入系统",
                            onClick = {
                                haptics.tick()
                                shareFile?.let { file ->
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "text/calendar")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching {
                                        context.startActivity(intent)
                                        onDismiss()
                                    }.onFailure {
                                        Toast.makeText(context, "未找到支持直接打开 .ics 的日历应用，请尝试分享", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        YohakuButton(
                            text = "分享文件",
                            onClick = {
                                haptics.tick()
                                shareFile?.let { file ->
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/calendar"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "分享日历文件 (.ics)"))
                                    onDismiss()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

package com.kxin.classtable.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kxin.classtable.data.RomHelper
import com.kxin.classtable.data.RomType
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuType

/**
 * 首次启动权限引导:列出提醒所需的四项(通知权限 / 精确闹钟 / 电池白名单 / 自启动),
 * 「继续开启」逐个引导(通知走系统请求,其余跳系统设置页,返回后自动推进下一项);
 * 「稍后再说」标记完成,之后可随时在设置页「提醒可靠性」重进。
 */
@Composable
fun PermissionOnboardingDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalYohakuColors.current
    val rom = remember { RomHelper.detect() }

    var notifOk by remember { mutableStateOf(RomHelper.notificationsEnabled(context)) }
    var alarmOk by remember { mutableStateOf(RomHelper.exactAlarmGranted(context)) }
    var batteryOk by remember { mutableStateOf(RomHelper.ignoreBatteryOptimizations(context)) }
    // 自启动无法自动检测状态,引导过一次即视为完成
    var autoStartVisited by remember { mutableStateOf(false) }

    // 从系统设置页返回时刷新状态,自动推进
    LifecycleResumeEffect(Unit) {
        notifOk = RomHelper.notificationsEnabled(context)
        alarmOk = RomHelper.exactAlarmGranted(context)
        batteryOk = RomHelper.ignoreBatteryOptimizations(context)
        onPauseOrDispose { }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 结果由 LifecycleResumeEffect 刷新 */ }

    // 可检测项全满足,且自启动已引导过(或非国产 ROM 无此项)→ 完成
    val allDone = notifOk && alarmOk && batteryOk &&
        (rom == RomType.STOCK || autoStartVisited)

    // 触发第一个未满足项
    fun openNext() {
        when {
            !notifOk -> notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            !alarmOk -> RomHelper.exactAlarmSettingsIntent(context)?.let { context.startActivity(it) }
            !batteryOk -> context.startActivity(RomHelper.batteryOptimizationIntent(context))
            rom != RomType.STOCK -> {
                autoStartVisited = true
                if (!RomHelper.tryOpenAutoStart(context)) {
                    context.startActivity(RomHelper.appDetailsIntent(context))
                }
            }
            else -> onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "开启提醒,不错过每节课", style = YohakuType.title20, color = colors.neutral10)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "课程提醒依赖系统闹钟与通知权限。国产 ROM 会默认限制后台,请逐项开启(约 1 分钟):",
                    style = YohakuType.label12,
                    color = colors.neutral7,
                )
                Spacer(modifier = Modifier.height(14.dp))
                PermissionRow("通知权限", notifOk, "显示课程提醒通知")
                Spacer(modifier = Modifier.height(10.dp))
                PermissionRow("精确闹钟", alarmOk, "准时触发提醒(Android 12+)")
                Spacer(modifier = Modifier.height(10.dp))
                PermissionRow("电池白名单", batteryOk, "防止系统清理闹钟")
                if (rom != RomType.STOCK) {
                    Spacer(modifier = Modifier.height(10.dp))
                    PermissionRow("自启动(${rom.label})", false, "开机后自动恢复提醒")
                }
            }
        },
        confirmButton = {
            Text(
                text = if (allDone) "完成" else "继续开启",
                style = YohakuType.copy14,
                color = colors.accent,
                modifier = Modifier
                    .clickable {
                        if (allDone) onDismiss() else openNext()
                    }
                    .padding(8.dp),
            )
        },
        dismissButton = {
            Text(
                text = "稍后再说",
                style = YohakuType.copy14,
                color = colors.neutral7,
                modifier = Modifier
                    .clickable { onDismiss() }
                    .padding(8.dp),
            )
        },
    )
}

@Composable
private fun PermissionRow(title: String, ok: Boolean, hint: String) {
    val colors = LocalYohakuColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = YohakuType.copy15, color = colors.neutral9)
            Text(text = hint, style = YohakuType.label12, color = colors.neutral6)
        }
        Text(
            text = if (ok) "已开启" else "未开启",
            style = YohakuType.label12,
            color = if (ok) colors.accent else colors.neutral7,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

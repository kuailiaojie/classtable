package com.kxin.classtable.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kxin.classtable.data.RomHelper
import com.kxin.classtable.data.RomType
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.ui.permissions.PermissionRow

/**
 * 首次启动全屏权限引导:解释缘由 + 逐项开启 + 实时状态。
 * 所有项开启后可「完成」;「跳过」后续可在设置页「提醒可靠性」重进。
 * 相比旧弹窗:全屏更清晰、每项可直接点击跳设置、完成前不隐形误触。
 */
@Composable
fun OnboardingScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalYohakuColors.current
    val rom = remember { RomHelper.detect() }

    var notifOk by remember { mutableStateOf(RomHelper.notificationsEnabled(context)) }
    var alarmOk by remember { mutableStateOf(RomHelper.exactAlarmGranted(context)) }
    var batteryOk by remember { mutableStateOf(RomHelper.ignoreBatteryOptimizations(context)) }
    var autoStartVisited by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        notifOk = RomHelper.notificationsEnabled(context)
        alarmOk = RomHelper.exactAlarmGranted(context)
        batteryOk = RomHelper.ignoreBatteryOptimizations(context)
        onPauseOrDispose { }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 状态由 LifecycleResumeEffect 刷新 */ }

    val allDone = notifOk && alarmOk && batteryOk &&
        (rom == RomType.STOCK || autoStartVisited)

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .background(colors.paper)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
        Column(modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding)) {
            Text(text = "开启提醒\n不错过每节课", style = YohakuType.title24, color = colors.neutral10)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "课程提醒依赖系统闹钟与通知权限。国产 ROM 会默认限制后台,请逐项开启(约 1 分钟)。这些也可稍后在「设置 → 提醒可靠性」重新打开。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Spacer(modifier = Modifier.height(14.dp))
        }

        Box(modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = YohakuDimens.screenPadding)
            .height(1.dp)
            .background(colors.neutral3))
        PermissionRow(
            title = "通知权限",
            ok = notifOk,
            hint = "显示课程提醒通知",
            onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        )
        Box(modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = YohakuDimens.screenPadding)
            .height(1.dp)
            .background(colors.neutral3))
        PermissionRow(
            title = "精确闹钟",
            ok = alarmOk,
            hint = "准时触发提醒(Android 12+)",
            onClick = { RomHelper.exactAlarmSettingsIntent(context)?.let { context.startActivity(it) } },
        )
        Box(modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = YohakuDimens.screenPadding)
            .height(1.dp)
            .background(colors.neutral3))
        PermissionRow(
            title = "电池白名单",
            ok = batteryOk,
            hint = "防止系统清理闹钟",
            onClick = { context.startActivity(RomHelper.batteryOptimizationIntent(context)) },
        )
        if (rom != RomType.STOCK) {
            Box(modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = YohakuDimens.screenPadding)
                .height(1.dp)
                .background(colors.neutral3))
            PermissionRow(
                title = "自启动(${rom.label})",
                ok = null,
                hint = "开机后自动恢复提醒",
                onClick = {
                    autoStartVisited = true
                    if (!RomHelper.tryOpenAutoStart(context)) {
                        context.startActivity(RomHelper.appDetailsIntent(context))
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

        Column(modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding)) {
            YohakuButton(
                text = if (allDone) "完成" else "继续开启",
                onClick = { if (allDone) onDismiss() else openNext() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "暂时跳过 · 稍后可在「设置」里重新开启",
                style = YohakuType.copy14,
                color = colors.neutral7,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 10.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

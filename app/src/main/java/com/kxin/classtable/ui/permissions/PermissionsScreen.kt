package com.kxin.classtable.ui.permissions

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.kxin.classtable.data.RomHelper
import com.kxin.classtable.data.RomType
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.notify.CapsuleCompat
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * 提醒可靠性(权限)管理页:集中展示并引导开启课程提醒所需的四项系统权限。
 * 从设置页「提醒可靠性」进入;首次启动的引导页也复用本列表。
 * 返回系统设置页后通过 [LifecycleResumeEffect] 自动刷新状态。
 */
@Composable
fun PermissionsScreen(
    nav: NavHostController,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val context = LocalContext.current
    val rom = remember { RomHelper.detect() }
    val autoStartVisited by viewModel.autoStartVisited.collectAsStateWithLifecycle()

    var notifOk by remember { mutableStateOf(RomHelper.notificationsEnabled(context)) }
    var alarmOk by remember { mutableStateOf(RomHelper.exactAlarmGranted(context)) }
    var batteryOk by remember { mutableStateOf(RomHelper.ignoreBatteryOptimizations(context)) }
    // 状态栏胶囊(实时活动)只在 API 36+ 存在系统开关;更早的系统没有这一项
    val capsuleSupported = Build.VERSION.SDK_INT >= 36
    var capsuleOk by remember { mutableStateOf(CapsuleCompat.canPostPromoted(context)) }

    // 从系统设置页返回时刷新状态
    LifecycleResumeEffect(Unit) {
        notifOk = RomHelper.notificationsEnabled(context)
        alarmOk = RomHelper.exactAlarmGranted(context)
        batteryOk = RomHelper.ignoreBatteryOptimizations(context)
        capsuleOk = CapsuleCompat.canPostPromoted(context)
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper)
            .verticalScroll(rememberScrollState()),
    ) {
        YohakuTopBar(title = "提醒可靠性", onBack = { nav.popBackStack() })

        Column(modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding)) {
            Text(
                text = "课程提醒依赖系统闹钟与通知权限。国产 ROM 默认限制后台,请逐项开启,防止提醒被系统清理:",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (rom == RomType.STOCK) {
                    "当前检测为原生系统。精确闹钟仍可能被清理,建议加入电池白名单。"
                } else {
                    "检测到 ${rom.label},建议开启自启动 + 电池白名单,确保到点必达。"
                },
                style = YohakuType.label12,
                color = colors.neutral6,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        Box(modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.neutral3))

        PermissionRow(
            title = "通知权限",
            ok = notifOk,
            hint = "显示课程提醒通知",
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            },
        )
        Box(modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = YohakuDimens.screenPadding)
            .height(1.dp)
            .background(colors.neutral3))
        if (capsuleSupported) {
            PermissionRow(
                title = "实时活动胶囊",
                ok = capsuleOk,
                hint = "让提醒出现在状态栏胶囊 / 灵动岛",
                onClick = {
                    runCatching {
                        context.startActivity(CapsuleCompat.promotedSettingsIntent(context))
                    }
                },
            )
            Box(modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = YohakuDimens.screenPadding)
                .height(1.dp)
                .background(colors.neutral3))
        }
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
                ok = if (autoStartVisited) true else null,
                hint = "开机后自动恢复提醒",
                onClick = {
                    viewModel.markAutoStartVisited()
                    if (!RomHelper.tryOpenAutoStart(context)) {
                        context.startActivity(RomHelper.appDetailsIntent(context))
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
    }
}

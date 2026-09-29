package com.kxin.classtable.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.kxin.classtable.data.Analytics
import com.kxin.classtable.data.RomHelper
import com.kxin.classtable.data.RomType
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuMotion
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.motionTimeline
import com.kxin.classtable.ui.permissions.PermissionRow
import com.kxin.classtable.ui.permissions.PermissionsViewModel

/**
 * 首次启动全屏权限引导:解释缘由 + 逐项开启 + 实时状态。
 * 所有项开启后可「完成」;「跳过」后续可在设置页「提醒可靠性」重进。
 * 相比旧弹窗:全屏更清晰、每项可直接点击跳设置、完成前不隐形误触。
 *
 * 整页按序入场(标题 → 逐项 → 按钮错峰),完成时按钮文案与进度一起变化。
 */
@Composable
fun OnboardingScreen(
    onDismiss: () -> Unit,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val colors = LocalYohakuColors.current
    val rom = remember { RomHelper.detect() }

    var notifOk by remember { mutableStateOf(RomHelper.notificationsEnabled(context)) }
    var alarmOk by remember { mutableStateOf(RomHelper.exactAlarmGranted(context)) }
    var batteryOk by remember { mutableStateOf(RomHelper.ignoreBatteryOptimizations(context)) }
    val autoStartVisited by viewModel.autoStartVisited.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { Analytics.log("onboarding_shown") }

    LifecycleResumeEffect(Unit) {
        notifOk = RomHelper.notificationsEnabled(context)
        alarmOk = RomHelper.exactAlarmGranted(context)
        batteryOk = RomHelper.ignoreBatteryOptimizations(context)
        onPauseOrDispose { }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 状态由 LifecycleResumeEffect 刷新 */ }

    val needsAutoStart = rom != RomType.STOCK
    val allDone = notifOk && alarmOk && batteryOk && (!needsAutoStart || autoStartVisited)
    val doneCount = listOfNotNull(
        notifOk,
        alarmOk,
        batteryOk,
        if (needsAutoStart) autoStartVisited else null,
    ).count { it }
    val totalCount = if (needsAutoStart) 4 else 3

    fun openNext() {
        when {
            !notifOk -> notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            !alarmOk -> RomHelper.exactAlarmSettingsIntent(context)?.let { context.startActivity(it) }
            !batteryOk -> context.startActivity(RomHelper.batteryOptimizationIntent(context))
            needsAutoStart -> {
                viewModel.markAutoStartVisited()
                if (!RomHelper.tryOpenAutoStart(context)) {
                    context.startActivity(RomHelper.appDetailsIntent(context))
                }
            }
            else -> onDismiss()
        }
    }

    // 整页按序入场(标题 → 逐项 → 底部按钮):一条时间线的 stagger 编排,而不是七个各自 delay。
    // 序号固定为 7(标题、说明、通知 / 精确闹钟 / 电池 / 自启动四行、底部按钮块)。
    val entrance = remember { List(ENTRANCE_STEPS) { Animatable(0f) } }
    LaunchedEffect(Unit) {
        motionTimeline {
            stagger(
                count = ENTRANCE_STEPS,
                stepMs = YohakuMotion.staggerLoose,
                durationMs = YohakuMotion.durSlow,
                position = "0",
            ) { index, duration ->
                entrance[index].animateTo(1f, YohakuMotion.tween(duration, YohakuMotion.easeExpoOut))
            }
        }
    }
    val header = entrance[0].value
    val desc = entrance[1].value
    val notifRow = entrance[2].value
    val alarmRow = entrance[3].value
    val batteryRow = entrance[4].value
    val autoStartRow = entrance[5].value
    val bottomBlock = entrance[6].value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .background(colors.paper)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
        Column(modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding)) {
            Text(
                text = "开启提醒\n不错过每节课",
                style = YohakuType.title24,
                color = colors.neutral10,
                modifier = Modifier.entrance(header),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "课程提醒依赖系统闹钟与通知权限。国产 ROM 会默认限制后台,请逐项开启(约 1 分钟)。这些也可稍后在「设置 → 提醒可靠性」重新打开。",
                style = YohakuType.label12,
                color = colors.neutral7,
                modifier = Modifier.entrance(desc),
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
            modifier = Modifier.entrance(notifRow),
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
            modifier = Modifier.entrance(alarmRow),
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
            modifier = Modifier.entrance(batteryRow),
        )
        if (needsAutoStart) {
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
                modifier = Modifier.entrance(autoStartRow),
            )
        }

        Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

        Column(
            modifier = Modifier
                .padding(horizontal = YohakuDimens.screenPadding)
                .entrance(bottomBlock),
        ) {
            Text(
                text = "已开启 $doneCount / $totalCount",
                style = YohakuType.label12,
                color = if (allDone) colors.accent else colors.neutral7,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            AnimatedContent(
                targetState = allDone,
                transitionSpec = {
                    fadeIn(YohakuMotion.tween(YohakuMotion.durFast)) togetherWith
                        fadeOut(YohakuMotion.tween(YohakuMotion.durFast))
                },
                label = "onboardingCta",
            ) { done ->
                YohakuButton(
                    text = if (done) "完成" else "继续开启",
                    onClick = {
                        if (allDone) {
                            Analytics.log("onboarding_completed")
                            onDismiss()
                        } else {
                            openNext()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "暂时跳过 · 稍后可在「设置」里重新开启",
                style = YohakuType.copy14,
                color = colors.neutral7,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        Analytics.log("onboarding_skipped")
                        onDismiss()
                    }
                    .padding(vertical = 10.dp),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

/** 入场编排的步数:标题、说明,通知 / 精确闹钟 / 电池白名单 / 自启动四行,再加底部按钮块。 */
private const val ENTRANCE_STEPS = 7

/** 把入场进度落到合成层:淡入 + 上移,不触发布局。 */
private fun Modifier.entrance(progress: Float): Modifier = graphicsLayer {
    alpha = progress
    translationY = (1f - progress) * 18f
}

package com.kxin.classtable.ui.settings

import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.kxin.classtable.BuildConfig
import com.kxin.classtable.data.SurveyPrompt
import com.kxin.classtable.data.importer.AdapterSource
import com.kxin.classtable.design.AccentOptions
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuOutlineButton
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.accentColor
import com.kxin.classtable.domain.Adjustments
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.IconCadence
import com.kxin.classtable.domain.model.NotifyMode
import com.kxin.classtable.domain.model.ThemeMode
import com.kxin.classtable.icon.AppIcon
import com.kxin.classtable.notify.DndController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置分组(二级页)。设置首页只列这些入口,常用且「即时生效」的设置项直接并进分组页,
 * 只有需要填字段 / 保存的复杂页(作息时间、调休、AI 密钥…)才保留各自的三级页。
 */
enum class SettingsHub(val title: String) {
    APPEARANCE("外观"),
    TIMETABLE("课表"),
    IMPORT("导入与识别"),
    REMINDER("提醒"),
    YUKETANG("雨课堂"),
    DESKTOP("桌面"),
    ACCOUNT("账号与数据"),
    ABOUT("关于"),
    ;

    /** 设置首页点进本分组的路由。 */
    val route: String get() = "settings_hub/$name"

    companion object {
        fun of(name: String?): SettingsHub = entries.firstOrNull { it.name == name } ?: APPEARANCE
    }
}

/** 设置行摘要:当前提醒形态(设置首页与「提醒」分组共用)。 */
internal fun reminderSummary(settings: AppSettings): String = when {
    !settings.notificationsEnabled -> "已关闭"
    settings.notifyMode == NotifyMode.LIVE.name && settings.notifyLeadMinutes <= 0 -> "准点 · 实时活动"
    settings.notifyMode == NotifyMode.LIVE.name -> "课前 ${settings.notifyLeadMinutes} 分钟 · 实时活动"
    settings.notifyLeadMinutes <= 0 -> "准点提醒"
    else -> "课前 ${settings.notifyLeadMinutes} 分钟"
}

/**
 * 设置分组页:标题 + 可滚动内容。每个「设置中心」的内容由各自的分组 Composable 提供。
 */
@Composable
fun SettingsHubScreen(
    nav: NavHostController,
    hub: SettingsHub,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
    ) {
        YohakuTopBar(title = hub.title, onBack = { nav.popBackStack() })
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
            when (hub) {
                SettingsHub.APPEARANCE -> AppearanceHub(viewModel)
                SettingsHub.TIMETABLE -> TimetableHub(nav, viewModel)
                SettingsHub.IMPORT -> ImportHub(nav, viewModel)
                SettingsHub.REMINDER -> ReminderHub(nav, viewModel)
                SettingsHub.YUKETANG -> YuketangHub(nav, viewModel)
                SettingsHub.DESKTOP -> DesktopHub(nav)
                SettingsHub.ACCOUNT -> AccountHub(nav, viewModel)
                SettingsHub.ABOUT -> AboutHub(nav)
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceHub(viewModel: SettingsViewModel) {
    val colors = LocalYohakuColors.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val iconViewModel: AppIconViewModel = hiltViewModel()
    val currentIcon = AppIcon.of(settings.appIconIndex)
    val cadence = IconCadence.of(settings.iconCarouselCadence)

    SettingsSection(title = "外观") {
        SettingBlock(title = "主题") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    YohakuChip(
                        text = when (mode) {
                            ThemeMode.SYSTEM -> "跟随系统"
                            ThemeMode.LIGHT -> "浅色"
                            ThemeMode.DARK -> "深色"
                        },
                        selected = settings.themeMode == mode,
                        onClick = { viewModel.setTheme(mode) },
                    )
                }
            }
        }
        DividerLine()
        SettingBlock(title = "强调色") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AccentOptions.forEach { (label, hex) ->
                    val selected = settings.accentHex.equals(hex, ignoreCase = true)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(accentColor(hex))
                                .then(
                                    if (selected) {
                                        Modifier.border(2.dp, colors.neutral10, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { viewModel.setAccent(hex) },
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = label,
                            style = YohakuType.label12,
                            color = if (selected) colors.neutral9 else colors.neutral7,
                        )
                    }
                }
            }
        }
    }

    // 应用图标:九宫格选图 + 轮播开关,都是即时生效,直接并进「外观」分组页
    SettingsSection(title = "应用图标") {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            IconGrid(current = currentIcon, onSelect = iconViewModel::select)
        }
        DividerLine()
        SettingBlock(
            title = "图标轮播",
            subtitle = "开启后自动在 9 张图之间轮换;关闭时保持你选中的那一张。",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YohakuChip(
                    text = "开启",
                    selected = settings.iconCarouselEnabled,
                    onClick = { iconViewModel.setCarousel(true, cadence) },
                )
                YohakuChip(
                    text = "关闭",
                    selected = !settings.iconCarouselEnabled,
                    onClick = { iconViewModel.setCarousel(false, cadence) },
                )
            }
        }
        DividerLine()
        SettingBlock(title = "轮换节奏") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconCadence.entries.forEach { option ->
                    YohakuChip(
                        text = option.label,
                        selected = cadence == option,
                        onClick = { iconViewModel.setCarousel(settings.iconCarouselEnabled, option) },
                    )
                }
            }
        }
    }

    Text(
        text = "桌面图标由系统缓存:切换后一般几秒内更新,个别第三方桌面要久一点 —— " +
            "若一直不变,把图标从桌面移除再重新添加即可。",
        style = YohakuType.label12,
        color = colors.neutral6,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

@Composable
private fun TimetableHub(nav: NavHostController, viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val periods = remember(settings.periodTimes) { Schedule.parsePeriods(settings.periodTimes) }
    val firstPeriodText = if (periods.isNotEmpty()) {
        "第1节 %02d:%02d".format(periods[0].start / 60, periods[0].start % 60)
    } else {
        "未设置"
    }
    val realWeek = Schedule.currentWeek(settings.semesterStartDay, settings.semesterWeekCount)
    val adjustmentDays = remember(settings.scheduleAdjustments) {
        Adjustments.decode(settings.scheduleAdjustments).size
    }

    SettingsSection(title = "课表") {
        SettingRow("作息时间", firstPeriodText) { nav.navigate("schedule_times") }
        DividerLine()
        SettingRow("学期周次", "当前第 $realWeek 周") { nav.navigate("semester") }
        DividerLine()
        SettingRow("调休课表", if (adjustmentDays == 0) "未设置" else "$adjustmentDays 天") {
            nav.navigate("adjustments")
        }
        DividerLine()
        SettingRow("课表显示", "课程块内容 / 样式 / 配色") { nav.navigate("timetable_display") }
    }
}

@Composable
private fun ImportHub(nav: NavHostController, viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    SettingsSection(title = "导入与识别") {
        SettingRow("教务导入", "3 步导入") { nav.navigate("import") }
        DividerLine()
        SettingRow(
            title = "AI 密钥",
            value = if (settings.aiApiKey.isBlank()) "未配置" else "已配置 · ${settings.provider().label}",
            onClick = { nav.navigate("ai_key") },
        )
    }

    AdapterSyncSection()
}

/** 适配器同步:状态 + 「立即同步」,都是即时操作,并进「导入与识别」分组页。 */
@Composable
private fun AdapterSyncSection() {
    val colors = LocalYohakuColors.current
    val context = LocalContext.current
    val adapterViewModel: AdapterSyncViewModel = hiltViewModel()
    val info by adapterViewModel.info.collectAsStateWithLifecycle()
    val busy by adapterViewModel.busy.collectAsStateWithLifecycle()
    val message by adapterViewModel.message.collectAsStateWithLifecycle()

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            adapterViewModel.consumeMessage()
        }
    }

    val synced = info?.source == AdapterSource.SYNCED
    val sourceLine = buildString {
        append(if (synced) "已同步(云端)" else "内置")
        append(" · ${info?.schoolCount ?: 0} 所学校 / ${info?.adapterCount ?: 0} 个适配器")
    }

    SettingsSection(title = "适配器同步") {
        SettingBlock(
            title = "学校与适配脚本",
            subtitle = "不定期从云端获取最新的学校索引与适配脚本;同步后导入页优先使用云端数据,失败或未同步时自动回退内置数据。",
        ) {
            Text(text = sourceLine, style = YohakuType.copy13, color = colors.neutral9)
            val syncedAt = info?.syncedAt ?: 0L
            if (synced && syncedAt > 0L) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "同步于 ${formatSyncedAt(syncedAt)}",
                    style = YohakuType.label12,
                    color = colors.neutral6,
                )
            }
        }
        DividerLine()
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            YohakuButton(
                text = if (busy) "同步中…" else "立即同步",
                onClick = { adapterViewModel.sync() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            if (synced) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "恢复内置适配器",
                    style = YohakuType.copy13,
                    color = colors.error,
                    modifier = Modifier
                        .clickable(enabled = !busy) { adapterViewModel.clear() }
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ReminderHub(nav: NavHostController, viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSection(title = "提醒") {
        SettingRow("课程提醒", reminderSummary(settings)) { nav.navigate("course_reminder") }
        DividerLine()
        SettingRow(
            title = "日程提醒",
            value = if (settings.agendaReminderEnabled) "每条可单独设" else "已关闭",
            onClick = { nav.navigate("agenda_reminder") },
        )
        DividerLine()
        SettingRow("提醒可靠性", "通知 / 闹钟 / 自启动") { nav.navigate("permissions") }
    }

    ClassDndSection(settings)
}

/** 上课免打扰:chip 开关 + 授权入口,即时生效,并进「提醒」分组页。 */
@Composable
private fun ClassDndSection(settings: AppSettings) {
    val colors = LocalYohakuColors.current
    val context = LocalContext.current
    val dndViewModel: ClassDndViewModel = hiltViewModel()
    var enabled by remember { mutableStateOf(settings.classDndEnabled) }
    var hydrated by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(DndController.isGranted(context)) }

    // settings 流先发占位再发真实值;等真实值到位后只灌一次
    LaunchedEffect(settings) {
        if (!hydrated && settings != AppSettings()) {
            enabled = settings.classDndEnabled
            hydrated = true
        }
    }
    // 从系统「勿扰访问权限」页返回时刷新授权状态
    LifecycleResumeEffect(Unit) {
        granted = DndController.isGranted(context)
        onPauseOrDispose { }
    }

    SettingsSection(title = "上课免打扰") {
        SettingBlock(
            title = "自动免打扰",
            subtitle = "每节课开始时把手机切进免打扰(完全静音),下课时退回原来的状态;连着上的课算作一段,中途不退出。",
        ) {
            ChipToggle(selected = enabled) {
                enabled = it
                dndViewModel.save(it)
            }
        }
        if (!granted) {
            DividerLine()
            SettingBlock(
                title = "勿扰访问权限",
                subtitle = "系统要求先授权,应用才能切换免打扰;开启开关后还需要在这里授权一次。",
            ) {
                YohakuOutlineButton(
                    text = "去系统设置授权",
                    onClick = { context.startActivity(DndController.settingsIntent()) },
                )
            }
        }
    }
}

@Composable
private fun YuketangHub(nav: NavHostController, viewModel: SettingsViewModel) {
    val yuketangLoggedIn by viewModel.yuketangLoggedIn.collectAsStateWithLifecycle()
    SettingsSection(title = "雨课堂") {
        SettingRow(
            title = "雨课堂公告",
            value = if (yuketangLoggedIn) "已登录" else "未登录",
            onClick = { nav.navigate("rain_classroom") },
        )
    }
}

@Composable
private fun DesktopHub(nav: NavHostController) {
    SettingsSection(title = "桌面") {
        SettingRow("桌面小组件", "今日 / 明日 / 下节课 / 日程 · 可自定义") { nav.navigate("widget_settings") }
    }
}

@Composable
private fun AccountHub(nav: NavHostController, viewModel: SettingsViewModel) {
    val userEmail by viewModel.userEmail.collectAsStateWithLifecycle()
    SettingsSection(title = "账号与数据") {
        SettingRow("账号与同步", userEmail ?: "未登录") { nav.navigate("account") }
    }
}

@Composable
private fun AboutHub(nav: NavHostController) {
    val uriHandler = LocalUriHandler.current
    SettingsSection(title = "关于") {
        SettingRow("检查更新", "") { nav.navigate("update") }
        DividerLine()
        SettingRow("关于", "v${BuildConfig.VERSION_NAME}") { nav.navigate("about") }
        DividerLine()
        // 邀请弹窗关了之后不是死路:这里常驻入口,想填随时能填
        SettingRow("用户问卷", "两分钟 · 帮我们改进") {
            runCatching { uriHandler.openUri(SurveyPrompt.FORM_URL) }
        }
    }
}

/** 3 列九宫格:一行三张,末行不足时补空位保持列宽一致。 */
@Composable
private fun IconGrid(current: AppIcon, onSelect: (AppIcon) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AppIcon.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { icon ->
                    IconTile(
                        icon = icon,
                        selected = icon == current,
                        onClick = { onSelect(icon) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun IconTile(
    icon: AppIcon,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    val shape = RoundedCornerShape(YohakuDimens.radiusCard)
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) colors.accent else colors.line,
                    shape = shape,
                ),
        ) {
            Image(
                painter = painterResource(icon.drawableRes),
                contentDescription = icon.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = icon.label,
            style = YohakuType.label12,
            color = if (selected) colors.accent else colors.neutral7,
        )
    }
}

private fun formatSyncedAt(millis: Long): String =
    if (millis <= 0L) {
        "—"
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }

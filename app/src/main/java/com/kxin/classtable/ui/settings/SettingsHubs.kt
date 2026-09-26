package com.kxin.classtable.ui.settings

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.kxin.classtable.BuildConfig
import com.kxin.classtable.data.SurveyPrompt
import com.kxin.classtable.design.AccentOptions
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.accentColor
import com.kxin.classtable.domain.Adjustments
import com.kxin.classtable.domain.Schedule
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.NotifyMode
import com.kxin.classtable.domain.model.ThemeMode
import com.kxin.classtable.icon.AppIcon

/**
 * 设置分组(二级页)。设置首页只列这些入口,原有设置项移入各自的页(三级页)。
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
 * 设置分组页:标题 + 可滚动内容。每个「设置中心」的内容由各自的分组 Composable 提供,
 * 里面才是原来的设置项(作息时间 / AI 密钥 / 应用图标…),它们各自有独立页面(三级页)。
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
                SettingsHub.APPEARANCE -> AppearanceHub(nav, viewModel)
                SettingsHub.TIMETABLE -> TimetableHub(nav, viewModel)
                SettingsHub.IMPORT -> ImportHub(nav, viewModel)
                SettingsHub.REMINDER -> ReminderHub(nav, viewModel)
                SettingsHub.YUKETANG -> YuketangHub(nav, viewModel)
                SettingsHub.DESKTOP -> DesktopHub(nav)
                SettingsHub.ACCOUNT -> AccountHub(nav, viewModel)
                SettingsHub.ABOUT -> AboutHub(nav)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceHub(nav: NavHostController, viewModel: SettingsViewModel) {
    val colors = LocalYohakuColors.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()

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
        DividerLine()
        SettingRow(
            title = "应用图标",
            value = AppIcon.of(settings.appIconIndex).label.let {
                if (settings.iconCarouselEnabled) "轮播中 · $it" else it
            },
            onClick = { nav.navigate("app_icon") },
        )
    }
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
        DividerLine()
        SettingRow("适配器同步", "更新学校与脚本") { nav.navigate("adapter_sync") }
    }
}

@Composable
private fun ReminderHub(nav: NavHostController, viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSection(title = "提醒") {
        SettingRow("课程提醒", reminderSummary(settings)) { nav.navigate("course_reminder") }
        DividerLine()
        SettingRow("提醒可靠性", "通知 / 闹钟 / 自启动") { nav.navigate("permissions") }
        DividerLine()
        SettingRow(
            title = "上课免打扰",
            value = if (settings.classDndEnabled) "自动进 / 退" else "已关闭",
            onClick = { nav.navigate("class_dnd") },
        )
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
        SettingRow("桌面小组件", "今日 / 明日 / 下节课 · 可自定义") { nav.navigate("widget_settings") }
    }
}

@Composable
private fun AccountHub(nav: NavHostController, viewModel: SettingsViewModel) {
    val userEmail by viewModel.userEmail.collectAsStateWithLifecycle()
    SettingsSection(title = "账号与数据") {
        SettingRow("账号", userEmail ?: "未登录") { nav.navigate("account") }
    }
}

@Composable
private fun AboutHub(nav: NavHostController) {
    val uriHandler = LocalUriHandler.current
    SettingsSection(title = "关于") {
        SettingRow("检查更新", "v${BuildConfig.VERSION_NAME}") { nav.navigate("update") }
        DividerLine()
        SettingRow("关于", "v${BuildConfig.VERSION_NAME}") { nav.navigate("about") }
        DividerLine()
        // 邀请弹窗关了之后不是死路:这里常驻入口,想填随时能填
        SettingRow("用户问卷", "两分钟 · 帮我们改进") {
            runCatching { uriHandler.openUri(SurveyPrompt.FORM_URL) }
        }
    }
}

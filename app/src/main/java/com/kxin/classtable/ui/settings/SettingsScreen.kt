package com.kxin.classtable.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.BuildConfig
import com.kxin.classtable.data.AuthRepository
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.data.SurveyPrompt
import com.kxin.classtable.data.TimetablePrefsStore
import com.kxin.classtable.data.yuketang.YuketangRepository
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.CourseColorScheme
import com.kxin.classtable.domain.model.IconCadence
import com.kxin.classtable.domain.model.ThemeMode
import com.kxin.classtable.icon.AppIcon
import com.kxin.classtable.icon.IconRotationScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val surveyPrompt: SurveyPrompt,
    yuketangRepository: YuketangRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    /** 「课表显示 → 配色方案」:全局派生课程淡彩时用多少个色相。 */
    val colorScheme: StateFlow<CourseColorScheme> = TimetablePrefsStore.flow(context)
        .map { it.colorScheme }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CourseColorScheme.STANDARD)

    val userEmail: StateFlow<String?> = authRepository.currentUser
        .map { it?.email }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 雨课堂登录态:决定设置页那一行的说明文字。 */
    val yuketangLoggedIn: StateFlow<Boolean> = yuketangRepository.loggedIn

    /** 够资格时弹一次用户问卷邀请(判定见 [SurveyPrompt])。 */
    val surveyInvite: StateFlow<Boolean> = surveyPrompt.shouldInvite
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 进入界面时记一次「打开过」;进程内只数一次。 */
    fun onAppOpened() = viewModelScope.launch { surveyPrompt.onAppOpened() }

    /** 问卷已处理:填了或不想填都算,之后不再自动弹。 */
    fun completeSurvey() = viewModelScope.launch { surveyPrompt.dismiss() }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }

    fun setAccent(hex: String) = viewModelScope.launch { settingsRepository.setAccent(hex) }

    /** 首次启动权限引导完成/跳过标记(只弹一次,设置页可随时重进)。 */
    fun completeOnboarding() =
        viewModelScope.launch { settingsRepository.setOnboardingDone() }

    /**
     * 「每次打开」节奏的图标轮播:本进程第一次真正进入界面时换下一张。
     * 后台节奏(每小时 / 每天)由 [com.kxin.classtable.icon.AppIconRotationWorker] 负责。
     */
    fun rotateIconOnLaunch() = viewModelScope.launch {
        val current = settingsRepository.currentSettings()
        val cadence = IconCadence.of(current.iconCarouselCadence)
        if (!IconRotationScheduler.claimLaunchRotation(current.iconCarouselEnabled, cadence)) return@launch
        settingsRepository.setAppIconIndex(AppIcon.nextIndex(current.appIconIndex))
    }
}

/**
 * 设置首页:只列[设置分组][SettingsHub]入口。
 *
 * 每张卡是一组语义相近的设置中心(外观 / 课表 / 导入与识别 / 提醒 / 雨课堂 / 桌面 / 账号与数据 /
 * 关于),点进去才是原来那些设置项 —— 于是「主题」「作息时间」「AI 密钥」这些选项各自落在**三级页**上,
 * 首页从此不会随着功能增加而越来越长。
 */
@Composable
fun SettingsScreen(
    nav: NavHostController,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val userEmail by viewModel.userEmail.collectAsStateWithLifecycle()
    val yuketangLoggedIn by viewModel.yuketangLoggedIn.collectAsStateWithLifecycle()

    val themeLabel = when (settings.themeMode) {
        ThemeMode.SYSTEM -> "跟随系统"
        ThemeMode.LIGHT -> "浅色"
        ThemeMode.DARK -> "深色"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper)
            .verticalScroll(rememberScrollState()),
    ) {
        // 设置是底部导航的根标签页,没有上一级可返回,因此这里不放返回箭头
        YohakuTopBar(title = "设置")

        SettingsSection(title = "常用") {
            SettingRow("外观", themeLabel) { nav.navigate(SettingsHub.APPEARANCE.route) }
            DividerLine()
            SettingRow("课表", "作息 / 周次 / 调休 / 显示") { nav.navigate(SettingsHub.TIMETABLE.route) }
            DividerLine()
            SettingRow("导入与识别", "教务 / AI / 适配器") { nav.navigate(SettingsHub.IMPORT.route) }
            DividerLine()
            SettingRow("提醒", reminderSummary(settings)) { nav.navigate(SettingsHub.REMINDER.route) }
        }

        SettingsSection(title = "更多") {
            SettingRow("雨课堂", if (yuketangLoggedIn) "已登录" else "未登录") {
                nav.navigate(SettingsHub.YUKETANG.route)
            }
            DividerLine()
            SettingRow("桌面", "小组件") { nav.navigate(SettingsHub.DESKTOP.route) }
            DividerLine()
            SettingRow("账号与数据", userEmail ?: "未登录") { nav.navigate(SettingsHub.ACCOUNT.route) }
            DividerLine()
            SettingRow("关于", "v${BuildConfig.VERSION_NAME}") { nav.navigate(SettingsHub.ABOUT.route) }
        }

        // 悬浮导航浮在内容之上:列表中途会从栏下穿过,末尾留出栏体高度,
        // 最后一行才不会被永久盖住。
        Spacer(modifier = Modifier.height(YohakuDimens.navReservedHeight))
    }
}

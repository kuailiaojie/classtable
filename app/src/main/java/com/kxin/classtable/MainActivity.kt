package com.kxin.classtable

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kxin.classtable.data.RomHelper
import com.kxin.classtable.data.RomType
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuBottomNav
import com.kxin.classtable.design.YohakuDialog
import com.kxin.classtable.design.YohakuDialogAction
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTheme
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.accentColor
import com.kxin.classtable.domain.model.ThemeMode
import com.kxin.classtable.notify.Notifier
import com.kxin.classtable.ui.navigateToTab
import com.kxin.classtable.ui.onboarding.OnboardingScreen
import com.kxin.classtable.ui.permissions.PermissionsScreen
import com.kxin.classtable.ui.account.AccountScreen
import com.kxin.classtable.ui.about.AboutScreen
import com.kxin.classtable.ui.courses.CourseDetailScreen
import com.kxin.classtable.ui.courses.CoursesScreen
import com.kxin.classtable.ui.day.DayScreen
import com.kxin.classtable.ui.form.CourseFormScreen
import com.kxin.classtable.ui.importer.AiImportScreen
import com.kxin.classtable.ui.importer.ImportScreen
import com.kxin.classtable.ui.importer.ManualImportScreen
import com.kxin.classtable.ui.settings.AdapterSyncScreen
import com.kxin.classtable.ui.settings.ScheduleTimesScreen
import com.kxin.classtable.ui.settings.SemesterScreen
import com.kxin.classtable.ui.settings.SettingsScreen
import com.kxin.classtable.ui.settings.SettingsViewModel
import com.kxin.classtable.ui.settings.UpdateScreen
import com.kxin.classtable.ui.settings.UpdateState
import com.kxin.classtable.ui.settings.UpdateViewModel
import com.kxin.classtable.ui.week.WeekScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ClasstableRoot() }
    }
}

@Composable
fun ClasstableRoot(
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    YohakuTheme(darkTheme = dark, accent = accentColor(settings.accentHex)) {
        val colors = LocalYohakuColors.current
        // 状态栏/导航栏图标深浅跟随应用主题(透明栏,底色由纸面铺满)
        val view = LocalView.current
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }

        val nav = rememberNavController()
        // 首次启动权限引导:未完成且存在未开启项 → 弹出(完成/跳过后只弹一次,设置页可重进)
        val context = LocalContext.current
        var onboardingDismissed by rememberSaveable { mutableStateOf(false) }
        val needsOnboarding = !settings.onboardingDone && !onboardingDismissed && (
            !RomHelper.notificationsEnabled(context) ||
                !RomHelper.exactAlarmGranted(context) ||
                !RomHelper.ignoreBatteryOptimizations(context) ||
                RomHelper.detect() != RomType.STOCK
        )
        // 通知点击 → 直达课程详情 / 检查更新页(仅处理进程首次带参启动,避免重组合重复导航)
        var handledDeepLink by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            val intent = context.findActivity()?.intent
            val courseId = runCatching { intent?.getStringExtra(Notifier.EXTRA_COURSE_ID) }.getOrNull()
            val openUpdate = runCatching {
                intent?.getBooleanExtra(Notifier.EXTRA_OPEN_UPDATE, false) ?: false
            }.getOrDefault(false)
            if (!handledDeepLink && !courseId.isNullOrBlank()) {
                handledDeepLink = true
                nav.navigate("course_detail/$courseId")
            } else if (!handledDeepLink && openUpdate) {
                handledDeepLink = true
                nav.navigate("update")
            }
        }
        // 纸面背景铺满全屏(含状态栏/导航栏区域),内容区再做系统栏内边距
        Box(modifier = Modifier.fillMaxSize().background(colors.paper)) {
            // 底部导航是悬浮层:只在四个根标签页显示,内容区在它上方结束(预留 navReservedHeight),
            // 二级页(课程详情、导入、设置子页…)占满整屏,不出现导航。
            val currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route
            val showBottomNav = currentRoute in ROOT_TABS
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .then(
                        if (showBottomNav) {
                            Modifier.padding(bottom = YohakuDimens.navReservedHeight)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                NavHost(navController = nav, startDestination = "week") {
                composable("week") { WeekScreen(nav) }
                composable("day") { DayScreen(nav) }
                composable(
                    route = "course_form?courseId={courseId}",
                    arguments = listOf(
                        navArgument("courseId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) { entry -> CourseFormScreen(nav, entry.arguments?.getString("courseId")) }
                composable("import") { ImportScreen(nav) }
                composable("import_manual") { ManualImportScreen(nav) }
                composable("import_ai") { AiImportScreen(nav) }
                composable("about") { AboutScreen(nav) }
                composable("courses") { CoursesScreen(nav) }
                composable(
                    route = "course_detail/{courseId}",
                    arguments = listOf(navArgument("courseId") { type = NavType.StringType }),
                ) { entry -> CourseDetailScreen(nav, entry.arguments?.getString("courseId") ?: "") }
                composable("settings") { SettingsScreen(nav) }
                composable("schedule_times") { ScheduleTimesScreen(nav) }
                composable("semester") { SemesterScreen(nav) }
                composable("permissions") { PermissionsScreen(nav) }
                composable("account") { AccountScreen(nav) }
                composable("adapter_sync") { AdapterSyncScreen(nav) }
                composable("update") { UpdateScreen(nav) }
                }
            }
            if (showBottomNav) {
                YohakuBottomNav(
                    current = currentRoute.orEmpty(),
                    onNavigate = { nav.navigateToTab(it) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        // 四周都要留白才是「悬浮」:左右由栏宽自带(只包标签、居中),
                        // 这里补上下的浮动余量,投影才有地方落。
                        .padding(vertical = 12.dp),
                )
            }
            // 首次启动权限引导全屏覆盖层:置于最上层,完成后 Dismiss 露出主界面
            if (needsOnboarding) {
                OnboardingScreen(
                    onDismiss = {
                        onboardingDismissed = true
                        settingsViewModel.completeOnboarding()
                    },
                )
            }
            // 启动静默检查更新(24h 节流);有新版弹非阻断提示
            var updateAutoChecked by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (!updateAutoChecked) {
                    updateAutoChecked = true
                    updateViewModel.autoCheck()
                }
            }
            (updateState as? UpdateState.Available)?.let { available ->
                YohakuDialog(
                    onDismissRequest = { updateViewModel.dismiss() },
                    title = "发现新版本 v${available.info.latestVersion}",
                    actions = {
                        YohakuDialogAction(
                            text = "忽略此版本",
                            onClick = { updateViewModel.ignoreVersion(available.info.latestVersion) },
                        )
                        YohakuDialogAction(
                            text = "以后再说",
                            onClick = { updateViewModel.dismiss() },
                        )
                        YohakuDialogAction(
                            text = "查看更新",
                            accent = true,
                            onClick = {
                                updateViewModel.dismiss()
                                nav.navigate("update")
                            },
                        )
                    },
                ) {
                    Text(
                        text = "当前版本 v${updateViewModel.currentVersion}。" +
                            "「忽略此版本」后不再提示,可在设置里手动检查。",
                        style = YohakuType.copy14,
                        color = LocalYohakuColors.current.neutral9,
                    )
                }
            }
        }
    }
}

/** 底部导航覆盖的四个根标签页(二级页不显示导航,占满整屏)。 */
private val ROOT_TABS = setOf("week", "day", "courses", "settings")

/** 沿 ContextWrapper 链向上找宿主 Activity。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

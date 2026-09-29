package com.kxin.classtable

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clipToBounds
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
import com.kxin.classtable.data.Analytics
import com.kxin.classtable.data.RemoteConfig
import com.kxin.classtable.data.RomHelper
import com.kxin.classtable.data.RomType
import com.kxin.classtable.data.SurveyPrompt
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuBottomNav
import com.kxin.classtable.design.YohakuDialog
import com.kxin.classtable.design.YohakuDialogAction
import com.kxin.classtable.design.YohakuTheme
import com.kxin.classtable.design.YohakuMotion
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.accentColor
import com.kxin.classtable.design.fadeScaleIn
import com.kxin.classtable.design.fadeScaleOut
import com.kxin.classtable.domain.model.AgendaCategory
import com.kxin.classtable.domain.model.ThemeMode
import com.kxin.classtable.notify.Notifier
import com.kxin.classtable.ui.navigateToTab
import com.kxin.classtable.ui.onboarding.OnboardingScreen
import com.kxin.classtable.ui.permissions.PermissionsScreen
import com.kxin.classtable.ui.account.AccountScreen
import com.kxin.classtable.ui.about.AboutScreen
import com.kxin.classtable.ui.agenda.AgendaFormScreen
import com.kxin.classtable.ui.agenda.AgendaScreen
import com.kxin.classtable.ui.courses.CourseDetailScreen
import com.kxin.classtable.ui.courses.CoursesScreen
import com.kxin.classtable.ui.form.CourseFormScreen
import com.kxin.classtable.ui.importer.AiImportScreen
import com.kxin.classtable.ui.importer.ImportScreen
import com.kxin.classtable.ui.importer.ManualImportScreen
import com.kxin.classtable.ui.settings.AdjustmentsScreen
import com.kxin.classtable.ui.settings.AgendaReminderScreen
import com.kxin.classtable.ui.settings.AiKeyScreen
import com.kxin.classtable.ui.settings.CourseReminderScreen
import com.kxin.classtable.ui.settings.ScheduleTimesScreen
import com.kxin.classtable.ui.settings.SemesterScreen
import com.kxin.classtable.ui.settings.SettingsHub
import com.kxin.classtable.ui.settings.SettingsHubScreen
import com.kxin.classtable.ui.settings.SettingsScreen
import com.kxin.classtable.ui.settings.SettingsViewModel
import com.kxin.classtable.ui.settings.TimetableDisplayScreen
import com.kxin.classtable.ui.settings.UpdateScreen
import com.kxin.classtable.ui.settings.UpdateState
import com.kxin.classtable.ui.settings.UpdateViewModel
import com.kxin.classtable.ui.settings.WidgetSettingsScreen
import com.kxin.classtable.ui.timetable.TimetableScreen
import com.kxin.classtable.ui.SplashOverlay
import com.kxin.classtable.ui.yuketang.RainClassroomScreen
import com.kxin.classtable.ui.yuketang.YuketangBindScreen
import com.kxin.classtable.ui.yuketang.YuketangLoginScreen
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
    val surveyInvite by settingsViewModel.surveyInvite.collectAsStateWithLifecycle()
    val courseScheme by settingsViewModel.colorScheme.collectAsStateWithLifecycle()
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    YohakuTheme(
        darkTheme = dark,
        accent = accentColor(settings.accentHex),
        courseScheme = courseScheme,
    ) {
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
        // 开屏退场后才让引导入场:两者接成一次连续编排,而不是引导在开屏后面「偷偷」播完
        // 远程开关(默认 true)可兜底关掉开屏;Remote Config 缓存值在下次启动生效
        val splashEnabled = remember { RemoteConfig.getBoolean("splash_enabled") }
        var splashExiting by remember { mutableStateOf(!splashEnabled) }
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
            val agendaId = runCatching { intent?.getStringExtra(Notifier.EXTRA_AGENDA_ID) }.getOrNull()
            val agendaDay = runCatching {
                intent?.getLongExtra(Notifier.EXTRA_AGENDA_DAY, 0L) ?: 0L
            }.getOrDefault(0L)
            val openUpdate = runCatching {
                intent?.getBooleanExtra(Notifier.EXTRA_OPEN_UPDATE, false) ?: false
            }.getOrDefault(false)
            val openRainClassroom = runCatching {
                intent?.getBooleanExtra(Notifier.EXTRA_OPEN_RAIN_CLASSROOM, false) ?: false
            }.getOrDefault(false)
            if (!handledDeepLink && !courseId.isNullOrBlank()) {
                handledDeepLink = true
                Analytics.log("reminder_tapped", "kind" to "course")
                nav.navigate("course_detail/$courseId")
            } else if (!handledDeepLink && !agendaId.isNullOrBlank()) {
                handledDeepLink = true
                Analytics.log("reminder_tapped", "kind" to "agenda")
                nav.navigate("agenda_form?eventId=$agendaId&date=$agendaDay")
            } else if (!handledDeepLink && openUpdate) {
                handledDeepLink = true
                Analytics.log("notification_tapped", "kind" to "update")
                nav.navigate("update")
            } else if (!handledDeepLink && openRainClassroom) {
                handledDeepLink = true
                Analytics.log("notification_tapped", "kind" to "rain_classroom")
                nav.navigate("rain_classroom")
            }
        }
        // 纸面背景铺满全屏(含状态栏/导航栏区域),内容区再做系统栏内边距
        Box(modifier = Modifier.fillMaxSize().background(colors.paper)) {
            // 底部导航是悬浮层:只在四个根标签页显示,二级页占满整屏。
            //
            // 内容区**不再给导航让出底部空间**(那是上一版的做法):让内容一直铺到系统手势条,
            // 导航栏才会压在内容之上,滑动时课程从栏下穿过 —— 这是「悬浮」与「贴底」的区别。
            // 内容不被最后一屏压住的问题,改由各根标签页在自己的滚动内容里预留
            // navReservedHeight 解决(预留量在滚动区内,所以中途照样会从栏下经过)。
            val currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route
            // 屏幕追踪:路由名即屏幕名
            LaunchedEffect(currentRoute) { currentRoute?.let { Analytics.screenView(it) } }
            val showBottomNav = currentRoute in ROOT_TABS
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            ) {
                NavHost(
                    navController = nav,
                    startDestination = "week",
                    // 转场期间两页会各自平移出屏幕;裁到内容区,别让退场页从系统栏那一侧漏出来
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                    enterTransition = { navEnter(initialState.destination.route, targetState.destination.route) },
                    exitTransition = { navExit(initialState.destination.route, targetState.destination.route) },
                    popEnterTransition = { navPopEnter() },
                    popExitTransition = { navPopExit() },
                    // Navigation 2.10 起,手势返回不再复用 popEnter/popExit,改走这两个参数;
                    // 缺省值是库的 DefaultNavTransitions(退场页 scaleOut(0.7) 缩向屏幕中央 +
                    // 底层页 fadeIn)——两层同时动还带 alpha,正好踩穿下面那条铁律(「关于」缩在
                    // 屏幕正中、压住「设置」的行内文字)。这里把手势路径钉回同一套:只让一层在动。
                    predictivePopEnterTransition = { navPopEnter() },
                    predictivePopExitTransition = { navPopExit() },
                ) {
                composable("week") { TimetableScreen(nav) }
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
                composable("agenda") { AgendaScreen(nav) }
                composable(
                    route = "agenda_form?eventId={eventId}&date={date}&category={category}",
                    arguments = listOf(
                        navArgument("eventId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        // 新建时的默认日期(epochDay);0 = 用今天
                        navArgument("date") {
                            type = NavType.LongType
                            defaultValue = 0L
                        },
                        // 新建时的默认分类(如从倒计时页签进来默认「考试」);缺省用「待办」
                        navArgument("category") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) { entry ->
                    AgendaFormScreen(
                        nav = nav,
                        eventId = entry.arguments?.getString("eventId"),
                        defaultDateEpoch = entry.arguments?.getLong("date") ?: 0L,
                        defaultCategory = entry.arguments?.getString("category")
                            ?.let { runCatching { AgendaCategory.valueOf(it) }.getOrNull() },
                    )
                }
                composable(
                    route = "course_detail/{courseId}",
                    arguments = listOf(navArgument("courseId") { type = NavType.StringType }),
                ) { entry -> CourseDetailScreen(nav, entry.arguments?.getString("courseId") ?: "") }
                composable("settings") { SettingsScreen(nav) }
                composable(
                    route = "settings_hub/{hub}",
                    arguments = listOf(navArgument("hub") { type = NavType.StringType }),
                ) { entry ->
                    SettingsHubScreen(nav, SettingsHub.of(entry.arguments?.getString("hub")))
                }
                composable("schedule_times") { ScheduleTimesScreen(nav) }
                composable("semester") { SemesterScreen(nav) }
                composable("adjustments") { AdjustmentsScreen(nav) }
                composable("widget_settings") { WidgetSettingsScreen(nav) }
                composable("timetable_display") { TimetableDisplayScreen(nav) }
                composable("permissions") { PermissionsScreen(nav) }
                composable("account") { AccountScreen(nav) }
                composable("ai_key") { AiKeyScreen(nav) }
                composable("course_reminder") { CourseReminderScreen(nav) }
                composable("agenda_reminder") { AgendaReminderScreen(nav) }
                composable("update") { UpdateScreen(nav) }
                composable("rain_classroom") { RainClassroomScreen(nav) }
                composable("yuketang_login") { YuketangLoginScreen(nav) }
                composable("yuketang_bind") { YuketangBindScreen(nav) }
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
                        .padding(vertical = 8.dp),
                )
            }
            // 首次启动权限引导全屏覆盖层:开屏退场后入场,完成后淡出露出主界面
            AnimatedVisibility(
                visible = needsOnboarding && splashExiting,
                enter = fadeScaleIn(),
                exit = fadeScaleOut(),
            ) {
                OnboardingScreen(
                    onDismiss = {
                        onboardingDismissed = true
                        settingsViewModel.completeOnboarding()
                    },
                )
            }
            if (splashEnabled) SplashOverlay(onExitStart = { splashExiting = true })
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

            // 记一次「打开过应用」,供问卷邀请判断时机(进程内一次)
            LaunchedEffect(Unit) { settingsViewModel.onAppOpened() }

            // 图标轮播的「每次打开」节奏:进程内第一次真正进界面时换下一张
            LaunchedEffect(Unit) { settingsViewModel.rotateIconOnLaunch() }

            // 用户问卷邀请:够资格才弹,且一次只弹一个 —— 权限引导、更新提示在时先让位
            val updateDialogShowing = updateState is UpdateState.Available
            if (surveyInvite && !needsOnboarding && !updateDialogShowing) {
                val uriHandler = LocalUriHandler.current
                YohakuDialog(
                    onDismissRequest = { settingsViewModel.completeSurvey() },
                    title = "用了一阵子了,想问几句",
                    actions = {
                        YohakuDialogAction(
                            text = "以后再说",
                            onClick = { settingsViewModel.completeSurvey() },
                        )
                        YohakuDialogAction(
                            text = "去填写",
                            accent = true,
                            onClick = {
                                settingsViewModel.completeSurvey()
                                runCatching { uriHandler.openUri(SurveyPrompt.FORM_URL) }
                            },
                        )
                    },
                ) {
                    Text(
                        text = "几个小问题,大约两分钟,不收集任何身份信息 —— " +
                            "你的回答会直接决定下一步先做什么。\n\n" +
                            "选「以后再说」就不再打扰;之后在「设置 → 关于」里也能随时找到。",
                        style = YohakuType.copy14,
                        color = LocalYohakuColors.current.neutral9,
                    )
                }
            }
        }
    }
}

/** 底部导航覆盖的根标签页(二级页不显示导航,占满整屏)。 */
private val ROOT_TABS = setOf("week", "courses", "agenda", "settings")

private fun isRootTab(route: String?): Boolean = route in ROOT_TABS

/**
 * 导航转场。
 *
 * 两个层级用两套动作:根标签之间是「同层切换」,不该有方向感 —— 淡出 → 淡入;
 * 二级页则是「上/下钻」,用共享轴水平滑入滑出,层级关系一眼可辨。
 *
 * **铁律:一次转场只让一层在动。** NavHost 把退场页与入场页叠在同一个容器里,而这两层
 * 谁画在上面**由库决定、并不保证** —— 预测性返回下尤其如此(见 Google Issue 345993681:
 * 返回手势进行中,被弹出的那一页有时会被画到另一页**后面**)。一旦两层同时平移,或同时
 * 改 alpha,重叠的那一帧就会把**错的那一页**画在上面:退场页的卡片压在入场页的文字上、
 * 把行内文字截断,看起来就是「两页同时出现又互相遮挡」。此前几版一直在调两页各自的位移
 * 与曲线(2.6.0 的分层视差、2.6.1 的「首尾相接」),都没离开「两层同时动」这个前提,所以
 * 始终没除根。正确的做法只有一个:**推进时只有入场页在动**(旧页原地不动,当作背景),
 * **返回时只有退场页在动**(被压住的那页原地不动、原地露出来)。这样无论库把哪一层画在
 * 上面,任意时刻屏上都只有一页的像素在变化,重叠也不可能看出来。
 *
 * 转场由**系统的返回手势驱动**(targetSdk 36 起预测性返回默认开启,且不再能关掉):手指
 * 拖到哪,退场页就停在哪、松手前随时能退回 —— 过程可控,而不是先放手、再看一段固定时长
 * 的动画。所以这里只用可被「拖动定位」的补间(slide + tween),不掺动画协程这类写死的
 * 驱动;被压住的那一页用 [EnterTransition.None] 保持在原地,不参与 seek。
 */
private fun navEnter(from: String?, to: String?): EnterTransition =
    if (isRootTab(from) && isRootTab(to)) {
        fadeIn(YohakuMotion.tween(YohakuMotion.durBase, delayMs = YohakuMotion.durFast)) +
            scaleIn(
                animationSpec = YohakuMotion.tween(
                    YohakuMotion.durBase,
                    YohakuMotion.easeOut,
                    YohakuMotion.durFast,
                ),
                initialScale = 0.98f,
            )
    } else {
        // 推进:只有入场页整幅推入,退场页原地不动(等它被新页盖住)
        slideInHorizontally(
            animationSpec = YohakuMotion.tween(YohakuMotion.durSlow, YohakuMotion.easeOut),
            initialOffsetX = { it },
        )
    }

private fun navExit(from: String?, to: String?): ExitTransition =
    if (isRootTab(from) && isRootTab(to)) {
        fadeOut(YohakuMotion.tween(YohakuMotion.durFast)) +
            scaleOut(
                animationSpec = YohakuMotion.tween(YohakuMotion.durFast, YohakuMotion.easeInOut),
                targetScale = 1.01f,
            )
    } else {
        // 推进时退场页不动 —— 只让入场页动,两层就永远不会同时出现在屏上(见上)
        ExitTransition.None
    }

/** 返回:只有当前页整幅向右滑出;被压住的那页原地不动、原地露出来。 */
private fun navPopEnter(): EnterTransition = EnterTransition.None

private fun navPopExit(): ExitTransition =
    slideOutHorizontally(
        animationSpec = YohakuMotion.tween(YohakuMotion.durSlow, YohakuMotion.easeOut),
        targetOffsetX = { it },
    )

/** 沿 ContextWrapper 链向上找宿主 Activity。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

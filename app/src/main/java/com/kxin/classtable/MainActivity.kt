package com.kxin.classtable

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuTheme
import com.kxin.classtable.design.accentColor
import com.kxin.classtable.domain.model.ThemeMode
import com.kxin.classtable.notify.Notifier
import com.kxin.classtable.ui.account.AccountScreen
import com.kxin.classtable.ui.about.AboutScreen
import com.kxin.classtable.ui.courses.CourseDetailScreen
import com.kxin.classtable.ui.courses.CoursesScreen
import com.kxin.classtable.ui.day.DayScreen
import com.kxin.classtable.ui.form.CourseFormScreen
import com.kxin.classtable.ui.importer.AiImportScreen
import com.kxin.classtable.ui.importer.ImportScreen
import com.kxin.classtable.ui.importer.ManualImportScreen
import com.kxin.classtable.ui.settings.ScheduleTimesScreen
import com.kxin.classtable.ui.settings.SemesterScreen
import com.kxin.classtable.ui.settings.SettingsScreen
import com.kxin.classtable.ui.settings.SettingsViewModel
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
fun ClasstableRoot(settingsViewModel: SettingsViewModel = hiltViewModel()) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
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
        // Android 13+ 通知权限:首次启动请求(拒绝不影响使用)
        val context = LocalContext.current
        val notifPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { /* 无论同意与否都不阻塞 */ }
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // 通知点击 → 直达课程详情(仅处理进程首次带参启动,避免重组合重复导航)
        var handledDeepLink by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            val courseId = runCatching {
                context.findActivity()?.intent?.getStringExtra(Notifier.EXTRA_COURSE_ID)
            }.getOrNull()
            if (!handledDeepLink && !courseId.isNullOrBlank()) {
                handledDeepLink = true
                nav.navigate("course_detail/$courseId")
            }
        }
        // 纸面背景铺满全屏(含状态栏/导航栏区域),内容区再做系统栏内边距
        Box(modifier = Modifier.fillMaxSize().background(colors.paper)) {
            Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
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
                composable("account") { AccountScreen(nav) }
                }
            }
        }
    }
}

/** 沿 ContextWrapper 链向上找宿主 Activity。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

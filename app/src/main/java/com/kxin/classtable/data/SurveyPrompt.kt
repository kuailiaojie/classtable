package com.kxin.classtable.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户问卷邀请。
 *
 * 时机不靠单一信号 —— 任一条单独拿来看都会误伤:刚装上就问、装了没用的也被问、
 * 空课表的人被问「觉得怎么样」。所以三个条件**同时**满足才开口:
 * 1. **装了够久**([MIN_AGE_MS]):躲开「刚下下来正在试」的那几天;
 * 2. **打开过够多次**([MIN_LAUNCHES]):说明会反复回来用,不是看一眼就卸;
 * 3. **建过至少一门课**:真的用起来了,才有反馈可说。
 *
 * 问过就再也不自动出现(见 [dismiss]),但表单入口常驻在「设置 → 关于」,想填随时能填。
 */
@Singleton
class SurveyPrompt @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: SettingsRepository,
    courses: CourseRepository,
) {
    /**
     * 「装了多久」取系统记录的**首次安装时间**,不用我们自己记的「第一次打开」:
     * 前者是跟随安装包的,应用升级不会清零 —— 已经在用的老用户升级上来就能立刻满足
     * 这一条(只要打开次数够),不必再白等一周。后者对老用户等于从零开始计时。
     */
    private val installedAt: Long by lazy {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        }.getOrDefault(0L)
    }

    /**
     * 进程内只数一次。计数**不能**放在 `Application.onCreate`:推送、桌面小组件、
     * WorkManager 定时任务都会把进程拉起来,那些不算「用户打开过应用」——
     * 照那样数,装了不用的用户也会攒够次数被问。
     */
    @Volatile
    private var countedThisProcess = false

    /** 是否该弹邀请。上游变化(或界面订阅)时评估一次,时间条件按评估那一刻算。 */
    val shouldInvite: Flow<Boolean> = combine(
        settings.usageStats,
        courses.observeAll(),
    ) { usage, all ->
        !usage.surveyHandled &&
            installedAt > 0 &&
            System.currentTimeMillis() - installedAt >= MIN_AGE_MS &&
            usage.launches >= MIN_LAUNCHES &&
            all.isNotEmpty()
    }

    /** 进入界面时记一次(进程内一次)。 */
    suspend fun onAppOpened() {
        if (countedThisProcess) return
        countedThisProcess = true
        settings.registerAppLaunch()
    }

    /** 处理掉邀请(填了或不想填都算),之后不再自动出现。 */
    suspend fun dismiss() = settings.markSurveyHandled()

    companion object {
        /** 用户调查表(Typeform)。 */
        const val FORM_URL = "https://form.typeform.com/to/DJex6MaE"

        /** 装上至少七天再问。 */
        private const val MIN_AGE_MS = 7L * 24 * 60 * 60 * 1000

        /** 且至少打开过八次。 */
        private const val MIN_LAUNCHES = 8
    }
}

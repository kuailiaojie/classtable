package com.kxin.classtable.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kxin.classtable.BuildConfig
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType

/**
 * 关于页:应用简介、设计理念、数据与隐私、教务适配致谢、
 * 开源组件许可与技术栈、版权与许可信息。
 */
@Composable
fun AboutScreen(nav: NavHostController) {
    val colors = LocalYohakuColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper)
            .verticalScroll(rememberScrollState()),
    ) {
        YohakuTopBar(title = "关于", onBack = { nav.popBackStack() })

        Column(modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding)) {
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
            Text(
                text = "课程表 · Yohaku 极简课程表",
                style = YohakuType.title28,
                color = colors.neutral10,
            )
            Text(
                text = "版本 ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
            Text(
                text = "一款面向在校大学生的极简效率型课程表应用。以单色克制与留白为设计语言,覆盖课表查看、课程导入、上课提醒与多端同步的完整闭环。",
                style = YohakuType.copy14,
                color = colors.neutral9,
            )

            SectionTitle("应用简介")
            Text(
                text = "课程表将一周课表组织为清晰的时间网格:普通课程按节次定位,自定义时间课程按起止时间定位,空堂以留白呈现。支持教务系统一键导入(正方 / 强智 / 青果 / URP / 超星等)、手动表格导入与 AI 图片识别三种方式;以内置精确闹钟保障上课提醒,并提供可选的云同步保障数据安全。",
                style = YohakuType.copy14,
                color = colors.neutral9,
            )

            SectionTitle("设计理念")
            Text(
                text = "• 课程不上色:全应用仅使用单一强调色,标记「此刻值得注意」的元素\n" +
                    "• 空堂即留白:无课的时间段保持纸面留白,不绘制任何占位装饰\n" +
                    "• 衬线纸感:内置思源宋体与等宽数字,呈现纸质课表的翻阅体验",
                style = YohakuType.copy14,
                color = colors.neutral9,
            )

            SectionTitle("数据与隐私")
            Text(
                text = "• 本地优先:课表数据存储于设备本地数据库,离线可用,不强制联网\n" +
                    "• 云同步可选:登录后同步至本人账号(Firebase Firestore),数据仅本人可见,可随时退出\n" +
                    "• 教务账号:仅在导入时用于本机 WebView 登录,凭证不离开设备\n" +
                    "• AI 密钥:由用户自行配置,仅存于本机,仅用于课表图片识别\n" +
                    "• 崩溃报告:经 Firebase Crashlytics 匿名上报,仅用于改进稳定性",
                style = YohakuType.copy14,
                color = colors.neutral9,
            )

            SectionTitle("教务适配")
            Text(
                text = "教务导入适配脚本来自开源项目 shiguang_warehouse(拾光课程表适配仓库),由社区维护,覆盖正方、强智、青果、URP、超星等主流教务系统。",
                style = YohakuType.copy14,
                color = colors.neutral9,
            )

            SectionTitle("开源组件")
            Text(
                text = "• shiguang_warehouse — MIT License\n" +
                    "• Noto Serif SC(思源宋体)— SIL Open Font License 1.1\n" +
                    "• JetBrains Mono — SIL Open Font License 1.1\n" +
                    "• Jetpack Compose / Room / DataStore / Hilt / WorkManager / Glance — Apache License 2.0\n" +
                    "• Firebase SDK(Analytics / Crashlytics / Messaging)— Apache License 2.0",
                style = YohakuType.copy14,
                color = colors.neutral9,
            )

            SectionTitle("技术栈")
            Text(
                text = "Kotlin · Jetpack Compose(自建 Yohaku Design System)· Room · DataStore · Hilt · Firebase Auth / Firestore / Analytics / Crashlytics / FCM · Glance 小组件",
                style = YohakuType.copy14,
                color = colors.neutral9,
            )

            SectionTitle("版权与许可")
            Text(
                text = "© 2026 kuailikaojie · 本项目基于 MIT 协议开源,完整许可见项目 LICENSE 文件。第三方组件遵循各自许可协议,详见上文。",
                style = YohakuType.copy14,
                color = colors.neutral9,
            )
            Spacer(modifier = Modifier.height(6.dp))
            val uriHandler = LocalUriHandler.current
            Text(
                text = "GitHub 仓库:github.com/kuailiaojie/classtable",
                style = YohakuType.copy14,
                color = colors.neutral9,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable {
                    uriHandler.openUri("https://github.com/kuailiaojie/classtable")
                },
            )

            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val colors = LocalYohakuColors.current
    Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
    Text(text = text, style = YohakuType.title20, color = colors.neutral10)
    Spacer(modifier = Modifier.height(6.dp))
    BoxLine(colors.neutral3)
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun BoxLine(color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

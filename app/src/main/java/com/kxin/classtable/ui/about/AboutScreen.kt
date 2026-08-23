package com.kxin.classtable.ui.about

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType

/** 关于页:版本、设计哲学、数据与隐私、开源致谢。 */
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
            Text(text = "课程表", style = YohakuType.title28, color = colors.neutral10)
            Text(text = "版本 0.1.0", style = YohakuType.label12, color = colors.neutral7)
            Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
            Text(
                text = "一款 Yohaku 风格的极简课程表:一抹 accent、三档中性、余者尽留为白。",
                style = YohakuType.copy14,
                color = colors.neutral9,
            )

            SectionTitle("设计哲学")
            Text(
                text = "• 课程不上色:单色克制,只用一个强调色指向「此刻值得注意的东西」\n" +
                    "• 空堂即留白:没有课的地方就是纸面,留白传递自由时间\n" +
                    "• 衬线纸感:思源宋体与等宽数字,像一张印好的纸质课表",
                style = YohakuType.copy14,
                color = colors.neutral9,
            )

            SectionTitle("数据与隐私")
            Text(
                text = "• 本地优先:课表数据存于本机 Room 数据库,离线可用\n" +
                    "• 同步可选:登录 Firebase 后同步到你的账号(Firestore,仅本人可见)\n" +
                    "• 教务账号:仅用于本机 WebView 登录教务系统,不上传任何服务器\n" +
                    "• AI 密钥:自备的 Gemini API Key 仅存本机设置,用于图片识别课表",
                style = YohakuType.copy14,
                color = colors.neutral9,
            )

            SectionTitle("教务适配")
            Text(
                text = "教务导入适配规则来自开源项目 shiguang_warehouse(拾光课程表适配仓库),社区维护,覆盖正方/强智/青果/URP/超星等主流教务系统。",
                style = YohakuType.copy14,
                color = colors.neutral9,
            )

            SectionTitle("开源致谢")
            Text(
                text = "• shiguang_warehouse — MIT,教务适配脚本\n" +
                    "• Noto Serif SC(思源宋体)— SIL OFL 1.1\n" +
                    "• JetBrains Mono — SIL OFL 1.1\n" +
                    "• Jetpack Compose / Room / Firebase / Glance — Apache 2.0",
                style = YohakuType.copy14,
                color = colors.neutral9,
            )

            SectionTitle("技术栈")
            Text(
                text = "Kotlin + Jetpack Compose(自建 Yohaku Design System)· Room · DataStore · Hilt · Firebase Auth/Firestore · Glance 小组件",
                style = YohakuType.copy14,
                color = colors.neutral9,
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
private fun BoxLine(color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

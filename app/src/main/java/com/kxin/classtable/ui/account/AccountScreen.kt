package com.kxin.classtable.ui.account

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTextField
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType

/** 账号页:Email/Password 登录或注册;未登录 = 访客本地模式。 */
@Composable
fun AccountScreen(
    nav: NavHostController,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val user by viewModel.user.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val syncOutcome by viewModel.syncOutcome.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 登录/注册后的同步结果:提示后返回设置页(拉取配置完成/失败都会返回)
    LaunchedEffect(syncOutcome) {
        val outcome = syncOutcome ?: return@LaunchedEffect
        if (!outcome.ok) {
            Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
        }
        viewModel.consumeSyncOutcome()
        nav.popBackStack()
    }

    var mode by rememberSaveable { mutableIntStateOf(0) } // 0 = 登录, 1 = 注册
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper)
            .verticalScroll(rememberScrollState()),
    ) {
        YohakuTopBar(title = "账号", onBack = { nav.popBackStack() })

        Column(modifier = Modifier.padding(horizontal = YohakuDimens.screenPadding)) {
            if (user != null) {
                Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
                Text(
                    text = "已登录:${user?.email}",
                    style = YohakuType.copy16,
                    color = colors.neutral10,
                )
                Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
                Text(
                    text = "课程与配置(作息/学期/提醒等)将同步到 Firestore(仅本人可见)。",
                    style = YohakuType.label12,
                    color = colors.neutral7,
                )
                Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
                YohakuButton(
                    text = if (busy) "同步中…" else "手动同步",
                    onClick = { viewModel.manualSync() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
                YohakuButton(
                    text = "退出登录",
                    onClick = { viewModel.signOut() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
                Text(
                    text = "忘记密码?发送重置邮件",
                    style = YohakuType.copy13,
                    color = colors.accent,
                    modifier = Modifier
                        .clickable { viewModel.resetPassword() }
                        .padding(vertical = 8.dp),
                )
            } else {
                Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    YohakuChip(text = "登录", selected = mode == 0, onClick = { mode = 0 })
                    YohakuChip(text = "注册", selected = mode == 1, onClick = { mode = 1 })
                }
                Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

                YohakuTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = "邮箱",
                    placeholder = "you@example.com",
                )
                Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

                YohakuTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "密码",
                    placeholder = "至少 6 位",
                )
                Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

                YohakuButton(
                    text = if (mode == 0) "登录" else "创建账号",
                    onClick = {
                        if (mode == 0) viewModel.signIn(email.trim(), password)
                        else viewModel.signUp(email.trim(), password)
                    },
                    enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            message?.let {
                Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
                Text(text = it, style = YohakuType.label12, color = colors.neutral7)
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
        }
    }
}

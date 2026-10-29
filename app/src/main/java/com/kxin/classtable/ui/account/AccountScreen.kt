package com.kxin.classtable.ui.account

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuConfirmDialog
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuOutlineButton
import com.kxin.classtable.design.YohakuTextField
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 账号与数据页:Email/Password 登录云同步，本地全量离线备份与恢复，及日历生态导出。 */
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

    // 退出登录确认
    var confirmSignOut by remember { mutableStateOf(false) }
    if (confirmSignOut) {
        YohakuConfirmDialog(
            title = "退出登录",
            message = "退出后将停止云同步,本机数据仍保留;重新登录可继续同步。",
            confirmText = "退出",
            destructive = false,
            onDismiss = { confirmSignOut = false },
            onConfirm = {
                confirmSignOut = false
                viewModel.signOut()
            },
        )
    }

    // 离线 JSON 导出 SAF 启动器
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        uri?.let {
            viewModel.exportBackup(it, context.contentResolver)
        }
    }

    // 离线 JSON 恢复 SAF 启动器与确认弹窗
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    if (pendingRestoreUri != null) {
        YohakuConfirmDialog(
            title = "恢复本地备份",
            message = "将把备份中的全部课程与日程追加合并至当前课表，并恢复作息时间与学期周次设置。是否继续？",
            confirmText = "确认恢复",
            destructive = false,
            onDismiss = { pendingRestoreUri = null },
            onConfirm = {
                val uri = pendingRestoreUri
                pendingRestoreUri = null
                if (uri != null) {
                    viewModel.restoreBackup(uri, context.contentResolver)
                }
            },
        )
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let {
            pendingRestoreUri = it
        }
    }

    // iCalendar (.ics) 导出 SAF 启动器
    val icsExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/calendar"),
    ) { uri: Uri? ->
        uri?.let {
            viewModel.exportIcs(it, context.contentResolver)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper)
            .verticalScroll(rememberScrollState()),
    ) {
        YohakuTopBar(title = "账号与数据", onBack = { nav.popBackStack() })

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
                    text = "课程与设置会同步到云端,仅你自己可见。",
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
                    onClick = { confirmSignOut = true },
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

            Spacer(modifier = Modifier.height(YohakuDimens.gapSection * 1.5f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.line),
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            // 本地数据与离线备份
            Text(
                text = "本地数据与离线备份",
                style = YohakuType.title20,
                color = colors.neutral10,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
            Text(
                text = "全量导出全部课程、日程待办与作息学期设置(.classtable.json)。无需联网或账号，可随时在新设备恢复或离线灾备。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                YohakuOutlineButton(
                    text = "导出备份文件",
                    onClick = {
                        val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                        exportLauncher.launch("余课备份_$dateStr.classtable.json")
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                YohakuOutlineButton(
                    text = "从备份恢复",
                    onClick = {
                        restoreLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(YohakuDimens.gapSection * 1.5f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.line),
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))

            // 日历同步与手表生态 (.ics)
            Text(
                text = "日历同步与手表生态 (.ics)",
                style = YohakuType.title20,
                color = colors.neutral10,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapTight))
            Text(
                text = "将全学期课程按周次时间生成标准 RFC 5545 iCalendar 文件，支持导入系统日历（小米、华为、OPPO、Apple、Google 日历等），打通智能手表/手环表盘日程提醒。",
                style = YohakuType.label12,
                color = colors.neutral7,
            )
            Spacer(modifier = Modifier.height(YohakuDimens.gapCard))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                YohakuOutlineButton(
                    text = "保存 .ics 文件",
                    onClick = {
                        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
                        icsExportLauncher.launch("余课课表_$dateStr.ics")
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                YohakuOutlineButton(
                    text = "发送至日历 / 手表",
                    onClick = {
                        viewModel.shareIcs { shareUri ->
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/calendar"
                                putExtra(Intent.EXTRA_STREAM, shareUri)
                                putExtra(Intent.EXTRA_SUBJECT, "余课课程表")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "发送课表到系统日历或设备"))
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
            }

            message?.let {
                Spacer(modifier = Modifier.height(YohakuDimens.gapCard))
                Text(text = it, style = YohakuType.copy13, color = colors.accent)
            }
            Spacer(modifier = Modifier.height(YohakuDimens.gapSection))
        }
    }
}

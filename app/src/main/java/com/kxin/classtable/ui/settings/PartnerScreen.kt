package com.kxin.classtable.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.AuthRepository
import com.kxin.classtable.data.CourseRepository
import com.kxin.classtable.data.PartnerRepository
import com.kxin.classtable.design.CoursePalette
import com.kxin.classtable.design.LocalCourseColorScheme
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuButton
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuConfirmDialog
import com.kxin.classtable.design.YohakuDialog
import com.kxin.classtable.design.YohakuDialogAction
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuOutlineButton
import com.kxin.classtable.design.YohakuTextField
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.design.rememberYohakuHaptics
import com.kxin.classtable.design.yohakuTouchTarget
import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.PartnerHuePalette
import com.kxin.classtable.domain.model.PartnerProfile
import com.kxin.classtable.domain.model.PartnerType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class PartnerViewModel @Inject constructor(
    private val partnerRepository: PartnerRepository,
    private val authRepository: AuthRepository,
    courseRepository: CourseRepository,
) : ViewModel() {
    val profiles: StateFlow<List<PartnerProfile>> = partnerRepository.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeProfile: StateFlow<PartnerProfile?> = partnerRepository.observeActiveProfile()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val courseCounts: StateFlow<Map<String, Int>> = partnerRepository.observeCourseCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val myCourses: StateFlow<List<Course>> = courseRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 云端分享与导入都要求已注册/登录。 */
    val signedIn: StateFlow<Boolean> = authRepository.currentUser
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authRepository.isSignedIn)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _shareCode = MutableStateFlow<String?>(null)
    val shareCode: StateFlow<String?> = _shareCode.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    /** 上传当前课表并生成 6 位云端提取码。 */
    fun publishCloudShare(
        ownerName: String,
        type: PartnerType,
        colorHue: Int,
        obscureCourseDetails: Boolean,
    ) {
        val uid = authRepository.uid
        if (uid == null) {
            _message.value = "云端分享需要先注册或登录账号"
            return
        }
        if (myCourses.value.isEmpty()) {
            _message.value = "当前学期还没有课程，先添加几门再分享吧"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            val token = authRepository.freshIdToken()
            if (token == null) {
                _message.value = "登录状态已失效，请重新登录"
                _busy.value = false
                return@launch
            }
            partnerRepository.publishShareToCloud(
                uid = uid,
                idToken = token,
                ownerName = ownerName,
                type = type,
                colorHue = colorHue,
                courses = myCourses.value,
                obscureCourseDetails = obscureCourseDetails,
            ).onSuccess { code ->
                _shareCode.value = code
                _message.value = "已生成提取码 $code，复制后发给对方即可"
            }.onFailure { e ->
                _message.value = e.message ?: "提取码生成失败，请稍后重试"
            }
            _busy.value = false
        }
    }

    /** 用 6 位提取码从云端导入并绑定对方课表。 */
    fun importCloudCode(code: String, onSuccess: () -> Unit = {}) {
        if (code.isBlank()) {
            _message.value = "请输入对方的 6 位提取码"
            return
        }
        if (authRepository.uid == null) {
            _message.value = "导入云端课表需要先注册或登录账号"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            val token = authRepository.freshIdToken()
            if (token == null) {
                _message.value = "登录状态已失效，请重新登录"
                _busy.value = false
                return@launch
            }
            partnerRepository.importFromCloudCode(code, token)
                .onSuccess { (profile, count) ->
                    _message.value = "已绑定「${profile.name}」的课表（共 $count 门课）"
                    onSuccess()
                }
                .onFailure { e ->
                    _message.value = e.message ?: "提取码导入失败，请核对后重试"
                }
            _busy.value = false
        }
    }

    fun setActive(partnerId: String) {
        viewModelScope.launch {
            partnerRepository.setActivePartner(partnerId)
            _message.value = "已切换当前展示对象"
        }
    }

    fun updateProfile(profile: PartnerProfile) {
        viewModelScope.launch {
            partnerRepository.updateProfile(profile)
            _message.value = "已保存「${profile.name}」的设定"
        }
    }

    fun deletePartner(profile: PartnerProfile) {
        viewModelScope.launch {
            partnerRepository.deletePartner(profile.id)
            _message.value = "已解绑并清除「${profile.name}」的课表数据"
        }
    }
}

/**
 * 朋友与情侣双人课表管理中心：
 * 1. 管理已绑定的伴侣/好友档案（切换活跃对象、修改昵称与关系、挑选专属和色、隐私解绑）；
 * 2. 用 6 位提取码从云端导入对方课表；
 * 3. 把课表上传到云端并生成提取码（支持隐私脱敏，仅共享忙闲时段）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartnerScreen(
    nav: NavHostController,
    viewModel: PartnerViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val scheme = LocalCourseColorScheme.current
    val dark = CoursePalette.isDarkTheme(colors.paper)
    val haptics = rememberYohakuHaptics()
    val context = LocalContext.current

    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val courseCounts by viewModel.courseCounts.collectAsStateWithLifecycle()
    val myCourses by viewModel.myCourses.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val shareCode by viewModel.shareCode.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    var importCode by rememberSaveable { mutableStateOf("") }
    var myNickname by rememberSaveable { mutableStateOf("我的课表") }
    var shareType by rememberSaveable { mutableStateOf(PartnerType.COUPLE) }
    var shareHue by rememberSaveable { mutableIntStateOf(PartnerType.COUPLE.defaultHue) }
    var obscureDetails by rememberSaveable { mutableStateOf(false) }

    var editingProfile by remember { mutableStateOf<PartnerProfile?>(null) }
    var unbindingProfile by remember { mutableStateOf<PartnerProfile?>(null) }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    editingProfile?.let { target ->
        PartnerEditDialog(
            profile = target,
            dark = dark,
            onDismiss = { editingProfile = null },
            onSave = { updated ->
                viewModel.updateProfile(updated)
                editingProfile = null
            },
        )
    }

    unbindingProfile?.let { target ->
        YohakuConfirmDialog(
            title = "解绑「${target.name}」的课表",
            message = "解绑后将从本机彻底清除「${target.name}」的全部课程数据，不会影响你自己的课表与提醒。",
            confirmText = "确认解绑",
            destructive = true,
            onConfirm = {
                viewModel.deletePartner(target)
                unbindingProfile = null
            },
            onDismiss = { unbindingProfile = null },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = YohakuDimens.maxContentWidth)
                .fillMaxWidth(),
        ) {
            YohakuTopBar(title = "双人课表", onBack = { nav.popBackStack() })

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(modifier = Modifier.height(YohakuDimens.gapCard))

                // 1. 已绑定的伴侣与好友列表
                SettingsSection(title = "已绑定的对象") {
                    if (profiles.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 14.dp),
                        ) {
                            Text(
                                text = "尚未绑定伴侣或好友课表",
                                style = YohakuType.copy15,
                                color = colors.neutral9,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "输入对方的 6 位云端提取码即可绑定；绑定后可在课表页顶栏一键切换「我的 / TA的 / 合盘」，并探测双方共同空闲时段。",
                                style = YohakuType.label12,
                                color = colors.neutral7,
                            )
                        }
                    } else {
                        profiles.forEachIndexed { idx, profile ->
                            if (idx > 0) DividerLine()
                            val count = courseCounts[profile.id] ?: 0
                            val markColor = CoursePalette.mark(profile.colorHue, dark, scheme)
                            val tintColor = CoursePalette.tint(profile.colorHue, dark, scheme)
                            val hueName = PartnerHuePalette.firstOrNull { it.hue == profile.colorHue }?.label ?: "自定义"

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(tintColor)
                                            .border(1.5.dp, markColor, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = profile.name.take(1),
                                            style = YohakuType.label12,
                                            color = colors.neutral10,
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = profile.name,
                                                style = YohakuType.copy15,
                                                color = colors.neutral10,
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(YohakuDimens.radiusChip),
                                                color = tintColor,
                                                border = BorderStroke(1.dp, colors.line),
                                            ) {
                                                Text(
                                                    text = "${profile.type.label} · $hueName",
                                                    style = YohakuType.label12,
                                                    color = colors.neutral9,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "$count 门课程 · 更新于 ${formatPartnerTime(profile.updatedAt)}",
                                            style = YohakuType.label12,
                                            color = colors.neutral6,
                                        )
                                    }
                                    if (profile.isCurrent) {
                                        Surface(
                                            shape = RoundedCornerShape(YohakuDimens.radiusChip),
                                            color = colors.neutral2,
                                            border = BorderStroke(1.dp, colors.accent),
                                        ) {
                                            Text(
                                                text = "当前合盘",
                                                style = YohakuType.label12,
                                                color = colors.accent,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            )
                                        }
                                    } else {
                                        Surface(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(YohakuDimens.radiusChip))
                                                .clickable {
                                                    haptics.tick()
                                                    viewModel.setActive(profile.id)
                                                },
                                            shape = RoundedCornerShape(YohakuDimens.radiusChip),
                                            color = colors.raised,
                                            border = BorderStroke(1.dp, colors.line),
                                        ) {
                                            Text(
                                                text = "设为当前",
                                                style = YohakuType.label12,
                                                color = colors.neutral8,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "设定关系与和色",
                                        style = YohakuType.copy13,
                                        color = colors.accent,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(YohakuDimens.radiusChip))
                                            .clickable {
                                                haptics.tick()
                                                editingProfile = profile
                                            }
                                            .padding(vertical = 2.dp),
                                    )
                                    Text(
                                        text = "去课表查看",
                                        style = YohakuType.copy13,
                                        color = colors.neutral8,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(YohakuDimens.radiusChip))
                                            .clickable {
                                                haptics.tick()
                                                if (!profile.isCurrent) {
                                                    viewModel.setActive(profile.id)
                                                }
                                                nav.popBackStack()
                                            }
                                            .padding(vertical = 2.dp),
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(
                                        text = "隐私解绑",
                                        style = YohakuType.copy13,
                                        color = colors.error,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(YohakuDimens.radiusChip))
                                            .clickable {
                                                haptics.tick()
                                                unbindingProfile = profile
                                            }
                                            .padding(vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. 用 6 位提取码从云端导入
                SettingsSection(title = "导入朋友 / 情侣课表") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        YohakuTextField(
                            value = importCode,
                            onValueChange = { input ->
                                importCode = input.uppercase()
                                    .filter { it in '0'..'9' || it in 'A'..'Z' }
                                    .take(6)
                            },
                            label = "6 位提取码",
                            placeholder = "例如 K7M2Q9",
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            YohakuOutlineButton(
                                text = "粘贴提取码",
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                    val pasted = text.uppercase()
                                        .filter { it in '0'..'9' || it in 'A'..'Z' }
                                        .take(6)
                                    if (pasted.length < 6) {
                                        Toast.makeText(context, "剪贴板里没有 6 位提取码", Toast.LENGTH_SHORT).show()
                                    } else {
                                        importCode = pasted
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (signedIn) {
                                YohakuButton(
                                    text = if (busy) "导入中…" else "从云端导入",
                                    onClick = {
                                        viewModel.importCloudCode(importCode) {
                                            importCode = ""
                                        }
                                    },
                                    enabled = !busy && importCode.length == 6,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                YohakuButton(
                                    text = "登录后导入",
                                    onClick = { nav.navigate("account") },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (!signedIn) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "云端课表需要登录账号才能读取；课表只会在你与分享者之间传递。",
                                style = YohakuType.label12,
                                color = colors.neutral6,
                            )
                        }
                    }
                }

                // 3. 云端分享我的课表（生成 6 位提取码，需先注册/登录）
                SettingsSection(title = "云端分享我的课表") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        YohakuTextField(
                            value = myNickname,
                            onValueChange = { myNickname = it },
                            label = "向对方展示的称呼",
                            placeholder = "例如：阿泽 / 小鹿",
                        )
                    }
                    DividerLine()
                    SettingBlock(
                        title = "关系定位",
                        subtitle = "对方导入后默认采用此关系标签与推荐配色，也可在对方手机上随时修改。",
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PartnerType.entries.forEach { t ->
                                YohakuChip(
                                    text = t.label,
                                    selected = shareType == t,
                                    onClick = {
                                        shareType = t
                                        shareHue = t.defaultHue
                                    },
                                )
                            }
                        }
                    }
                    DividerLine()
                    SettingBlock(title = "推荐专属和色") {
                        PartnerHueSelector(
                            selectedHue = shareHue,
                            dark = dark,
                            onSelectHue = { shareHue = it },
                        )
                    }
                    DividerLine()
                    SettingSwitchRow(
                        title = "仅共享忙闲时段（隐私脱敏）",
                        subtitle = "开启后隐藏具体课程名、教室与教师，对方课表上仅显示「忙碌(有课)」。",
                        checked = obscureDetails,
                        onCheckedChange = { obscureDetails = it },
                    )
                    DividerLine()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        if (!signedIn) {
                            Text(
                                text = "云端分享需要先注册或登录账号。生成 6 位提取码后，对方在余课里输入即可导入你的课表。",
                                style = YohakuType.label12,
                                color = colors.neutral7,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            YohakuButton(
                                text = "登录 / 注册账号",
                                onClick = { nav.navigate("account") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(
                                text = "当前学期共有 ${myCourses.size} 门课程，将上传课程、周次到云端并生成 6 位提取码。",
                                style = YohakuType.label12,
                                color = colors.neutral6,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            YohakuButton(
                                text = if (busy) "生成中…" else if (shareCode == null) "生成提取码" else "重新生成提取码",
                                onClick = {
                                    viewModel.publishCloudShare(
                                        ownerName = myNickname,
                                        type = shareType,
                                        colorHue = shareHue,
                                        obscureCourseDetails = obscureDetails,
                                    )
                                },
                                enabled = !busy && myCourses.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                            )

                            val code = shareCode
                            if (code != null) {
                                Spacer(modifier = Modifier.height(12.dp))
                                CloudShareCodeCard(
                                    code = code,
                                    onCopy = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        cm?.setPrimaryClip(ClipData.newPlainText("余课提取码", code))
                                        Toast.makeText(context, "提取码已复制", Toast.LENGTH_SHORT).show()
                                    },
                                    onShare = {
                                        val text = "我在余课分享了课表，打开 App →「双人课表」→ 输入提取码 $code 即可导入。"
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, text)
                                        }
                                        runCatching {
                                            context.startActivity(Intent.createChooser(sendIntent, "分享我的余课课表"))
                                        }
                                    },
                                )
                            } else {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "提取码生成后只在本页展示，复制发送给对方即可；对方也需登录余课。",
                                    style = YohakuType.label12,
                                    color = colors.neutral6,
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "隐私与隔离保障：伴侣与好友的课程存储在独立的沙盒数据表中，绝不会触发你的上课提醒闹钟、免打扰或锁屏实况胶囊；云端分享仅在登录后上传一份课表快照，解绑即彻底清空本机数据。",
                    style = YohakuType.label12,
                    color = colors.neutral6,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(modifier = Modifier.height(YohakuDimens.gapSection * 2))
            }
        }
    }
}

/** 云端分享结果：6 位提取码像一张小票，复制 / 分享都收在卡内。 */
@Composable
private fun CloudShareCodeCard(
    code: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    val colors = LocalYohakuColors.current
    val haptics = rememberYohakuHaptics()
    val shape = RoundedCornerShape(YohakuDimens.radiusControl)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.neutral2)
            .border(1.dp, colors.line, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(text = "对方提取码", style = YohakuType.label12, color = colors.neutral7)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = code,
            style = YohakuType.timeMono.copy(fontSize = 24.sp, letterSpacing = 4.sp),
            color = colors.neutral10,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "复制提取码",
                style = YohakuType.copy13,
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(YohakuDimens.radiusChip))
                    .clickable {
                        haptics.tick()
                        onCopy()
                    }
                    .padding(vertical = 2.dp),
            )
            Text(
                text = "发送给好友",
                style = YohakuType.copy13,
                color = colors.neutral8,
                modifier = Modifier
                    .clip(RoundedCornerShape(YohakuDimens.radiusChip))
                    .clickable {
                        haptics.tick()
                        onShare()
                    }
                    .padding(vertical = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PartnerHueSelector(
    selectedHue: Int,
    dark: Boolean,
    onSelectHue: (Int) -> Unit,
) {
    val colors = LocalYohakuColors.current
    val scheme = LocalCourseColorScheme.current
    val haptics = rememberYohakuHaptics()
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PartnerHuePalette.forEach { option ->
            val selected = selectedHue == option.hue
            val swatchColor = CoursePalette.mark(option.hue, dark, scheme)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .yohakuTouchTarget()
                        .clickable {
                            haptics.tick()
                            onSelectHue(option.hue)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .then(
                                if (selected) {
                                    Modifier.border(2.dp, colors.neutral10, CircleShape)
                                } else {
                                    Modifier.border(1.dp, colors.line, CircleShape)
                                },
                            )
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(swatchColor),
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = option.label,
                    style = YohakuType.label12,
                    color = if (selected) colors.neutral10 else colors.neutral7,
                )
            }
        }
    }
}

@Composable
private fun PartnerEditDialog(
    profile: PartnerProfile,
    dark: Boolean,
    onDismiss: () -> Unit,
    onSave: (PartnerProfile) -> Unit,
) {
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
    var type by rememberSaveable(profile.id) { mutableStateOf(profile.type) }
    var hue by rememberSaveable(profile.id) { mutableIntStateOf(profile.colorHue) }

    YohakuDialog(
        onDismissRequest = onDismiss,
        title = "设定「${profile.name}」",
        actions = {
            YohakuDialogAction(text = "取消", onClick = onDismiss)
            YohakuDialogAction(
                text = "保存",
                accent = true,
                onClick = {
                    val trimmed = name.trim().ifEmpty { profile.name }
                    onSave(
                        profile.copy(
                            name = trimmed,
                            type = type,
                            colorHue = hue,
                        ),
                    )
                },
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            YohakuTextField(
                value = name,
                onValueChange = { name = it },
                label = "备注称呼",
                placeholder = "例如：小鹿",
            )
            Column {
                Text(
                    text = "关系定位",
                    style = YohakuType.label12,
                    color = LocalYohakuColors.current.neutral7,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PartnerType.entries.forEach { t ->
                        YohakuChip(
                            text = t.label,
                            selected = type == t,
                            onClick = { type = t },
                        )
                    }
                }
            }
            Column {
                Text(
                    text = "专属和色（用于合盘与TA的课表渲染）",
                    style = YohakuType.label12,
                    color = LocalYohakuColors.current.neutral7,
                )
                Spacer(modifier = Modifier.height(6.dp))
                PartnerHueSelector(
                    selectedHue = hue,
                    dark = dark,
                    onSelectHue = { hue = it },
                )
            }
        }
    }
}

private fun formatPartnerTime(millis: Long): String =
    if (millis <= 0L) {
        "刚刚"
    } else {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }

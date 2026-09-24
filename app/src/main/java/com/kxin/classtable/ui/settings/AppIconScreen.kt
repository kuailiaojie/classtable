package com.kxin.classtable.ui.settings

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.kxin.classtable.data.SettingsRepository
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuChip
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuTopBar
import com.kxin.classtable.design.YohakuType
import com.kxin.classtable.domain.model.AppSettings
import com.kxin.classtable.domain.model.IconCadence
import com.kxin.classtable.icon.AppIcon
import com.kxin.classtable.icon.IconRotationScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppIconViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    /** 手动选一张。真正的 alias 切换由 ClasstableApp 里那条设置流统一做。 */
    fun select(icon: AppIcon) = viewModelScope.launch {
        settingsRepository.setAppIconIndex(icon.ordinal)
    }

    /** 轮播开关与节奏:存下来,并按新节奏重排后台任务。 */
    fun setCarousel(enabled: Boolean, cadence: IconCadence) = viewModelScope.launch {
        settingsRepository.setIconCarousel(enabled, cadence)
        IconRotationScheduler.sync(context, enabled, cadence)
    }
}

/** 应用图标页:9 张角色图任选 + 轮播开关与节奏。 */
@Composable
fun AppIconScreen(
    nav: NavHostController,
    viewModel: AppIconViewModel = hiltViewModel(),
) {
    val colors = LocalYohakuColors.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val current = AppIcon.of(settings.appIconIndex)
    val cadence = IconCadence.of(settings.iconCarouselCadence)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper)
            .verticalScroll(rememberScrollState()),
    ) {
        YohakuTopBar(title = "应用图标", onBack = { nav.popBackStack() })

        SettingsSection(title = "应用图标") {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                IconGrid(current = current, onSelect = viewModel::select)
            }
        }

        SettingsSection(title = "图标轮播") {
            SettingBlock(
                title = "图标轮播",
                subtitle = "开启后自动在 9 张图之间轮换;关闭时保持你选中的那一张。",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    YohakuChip(
                        text = "开启",
                        selected = settings.iconCarouselEnabled,
                        onClick = { viewModel.setCarousel(true, cadence) },
                    )
                    YohakuChip(
                        text = "关闭",
                        selected = !settings.iconCarouselEnabled,
                        onClick = { viewModel.setCarousel(false, cadence) },
                    )
                }
            }
            DividerLine()
            SettingBlock(title = "轮换节奏") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconCadence.entries.forEach { option ->
                        YohakuChip(
                            text = option.label,
                            selected = cadence == option,
                            onClick = { viewModel.setCarousel(settings.iconCarouselEnabled, option) },
                        )
                    }
                }
            }
        }

        Text(
            text = "桌面图标由系统缓存:切换后一般几秒内更新,个别第三方桌面要久一点 —— " +
                "若一直不变,把图标从桌面移除再重新添加即可。",
            style = YohakuType.label12,
            color = colors.neutral6,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        // 二级页不显示悬浮导航,因此不需要给它预留高度(多留会多出一段空白)。
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** 3 列九宫格:一行三张,末行不足时补空位保持列宽一致。 */
@Composable
private fun IconGrid(current: AppIcon, onSelect: (AppIcon) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AppIcon.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { icon ->
                    IconTile(
                        icon = icon,
                        selected = icon == current,
                        onClick = { onSelect(icon) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun IconTile(
    icon: AppIcon,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    val shape = RoundedCornerShape(YohakuDimens.radiusCard)
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) colors.accent else colors.line,
                    shape = shape,
                ),
        ) {
            Image(
                painter = painterResource(icon.drawableRes),
                contentDescription = icon.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = icon.label,
            style = YohakuType.label12,
            color = if (selected) colors.accent else colors.neutral7,
        )
    }
}

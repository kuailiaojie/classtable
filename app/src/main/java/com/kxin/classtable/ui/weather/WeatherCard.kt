package com.kxin.classtable.ui.weather

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.kxin.classtable.data.Weather
import com.kxin.classtable.design.LocalYohakuColors
import com.kxin.classtable.design.YohakuDimens
import com.kxin.classtable.design.YohakuType
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 天气图标:自绘的细笔画(不用 emoji / 图标字体),颜色只取中性档,不占用 accent。
 * 表头左上角的「当前天气」与 [WeatherCard] 共用同一个图标。
 */
@Composable
fun WeatherIcon(weather: Weather, modifier: Modifier = Modifier) {
    val colors = LocalYohakuColors.current
    val ink = colors.neutral7
    val soft = colors.neutral5
    val kind = weatherKind(weather)
    Canvas(modifier) {
        when (kind) {
            WeatherKind.CLEAR ->
                drawSun(Offset(size.width * 0.5f, size.height * 0.5f), size.width * 0.17f, ink)

            WeatherKind.PARTLY -> {
                // 云是不透明实心,后画就自然遮住太阳的下半——正好是「晴间多云」的遮挡关系
                drawSun(Offset(size.width * 0.36f, size.height * 0.30f), size.width * 0.115f, ink)
                drawCloud(ink, scale = 0.88f, shiftY = 0.10f)
            }

            WeatherKind.OVERCAST -> {
                drawCloud(soft, scale = 0.88f, shiftX = 0.07f, shiftY = -0.06f)
                drawCloud(ink, scale = 0.88f, shiftX = -0.03f, shiftY = 0.08f)
            }

            WeatherKind.RAIN -> {
                drawCloud(ink, scale = 0.92f, shiftY = -0.12f)
                drawDrops(soft)
            }

            WeatherKind.THUNDER -> {
                drawCloud(ink, scale = 0.92f, shiftY = -0.14f)
                drawBolt(ink)
            }

            WeatherKind.SNOW -> {
                drawCloud(ink, scale = 0.92f, shiftY = -0.12f)
                drawFlakes(soft)
            }

            WeatherKind.SLEET -> {
                drawCloud(ink, scale = 0.92f, shiftY = -0.12f)
                drawDrops(soft, only = 0)
                drawFlakes(soft, only = 1)
            }

            WeatherKind.FOG -> drawFog(ink, soft)

            WeatherKind.WIND -> drawWind(ink, soft)

            WeatherKind.UNKNOWN -> drawCloud(ink)
        }
    }
}

/**
 * 当前天气卡:一行大字温度 + 一行小字要素,右侧定位与今日高低温。
 * 整块可点,点一下重新拉取。
 */
@Composable
fun WeatherCard(
    weather: Weather,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalYohakuColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(YohakuDimens.radiusCard),
        color = colors.raised,
        border = BorderStroke(1.dp, colors.line),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onRefresh)
                .padding(YohakuDimens.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WeatherIcon(weather, modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(YohakuDimens.gapCard))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${weather.temperature.roundToInt()}°",
                        style = YohakuType.title28,
                        color = colors.neutral10,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = weather.text,
                        style = YohakuType.copy13,
                        color = colors.neutral7,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
                metaText(weather)?.let {
                    Text(
                        text = it,
                        style = YohakuType.label12,
                        color = colors.neutral7,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(start = YohakuDimens.gapTight),
            ) {
                if (weather.place.isNotEmpty()) {
                    Text(
                        text = weather.place,
                        style = YohakuType.label12,
                        color = colors.neutral7,
                    )
                }
                rangeText(weather)?.let {
                    Text(
                        text = it,
                        style = YohakuType.timeMono,
                        color = colors.neutral8,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** 体感 / 湿度 / 风 / 空气质量 / 紫外线,按可用项拼一行;全缺时返回 null。 */
private fun metaText(weather: Weather): String? {
    val wind = listOf(weather.windDirection, weather.windPower)
        .filter { it.isNotEmpty() }
        .joinToString(" ")
    val facts = buildList {
        weather.feelsLike?.let { add("体感 ${it.roundToInt()}°") }
        weather.humidity?.let { add("湿度 $it%") }
        if (wind.isNotEmpty()) add(wind)
        weather.aqiCategory?.let { add("空气 $it") }
        weather.uv?.let { add("紫外线 ${it.roundToInt()}") }
    }
    return facts.joinToString(" · ").ifEmpty { null }
}

private fun rangeText(weather: Weather): String? {
    val max = weather.tempMax ?: return null
    val min = weather.tempMin ?: return null
    return "↑${max.roundToInt()}° ↓${min.roundToInt()}°"
}

/** 天气现象归类。优先认中文文案(接口的 weather 始终是人话),认不出再退回 weather_icon。 */
private enum class WeatherKind { CLEAR, PARTLY, OVERCAST, RAIN, THUNDER, SNOW, SLEET, FOG, WIND, UNKNOWN }

private fun weatherKind(weather: Weather): WeatherKind {
    val text = weather.text
    return when {
        // 复合现象要先判:「雨夹雪」雨雪都有,「雷阵雨」也算雨
        "雷" in text -> WeatherKind.THUNDER
        "雨夹雪" in text || "雨雪" in text -> WeatherKind.SLEET
        "雪" in text -> WeatherKind.SNOW
        "雨" in text -> WeatherKind.RAIN
        "雾" in text || "霾" in text || "沙" in text || "尘" in text -> WeatherKind.FOG
        "风" in text -> WeatherKind.WIND
        "阴" in text -> WeatherKind.OVERCAST
        "多云" in text || "少云" in text || "晴间" in text -> WeatherKind.PARTLY
        "晴" in text -> WeatherKind.CLEAR
        // 和风系图标码:1xx 晴云 / 2xx 风 / 3xx 雨 / 4xx 雪 / 5xx 雾霾沙
        else -> when (weather.icon.firstOrNull()) {
            '1' -> when (weather.icon.lastOrNull()) {
                '0' -> WeatherKind.CLEAR
                '4' -> WeatherKind.OVERCAST
                else -> WeatherKind.PARTLY
            }
            '2' -> WeatherKind.WIND
            '3' -> WeatherKind.RAIN
            '4' -> WeatherKind.SNOW
            '5' -> WeatherKind.FOG
            else -> WeatherKind.UNKNOWN
        }
    }
}

/**
 * 云。三团重叠的**实心**圆 + 一条圆角底座拼成:同色重叠自然并成一朵云,
 * 不用描边(描边会把每团的内部弧线也画出来,反而穿帮)。
 * 按 44dp 方框设计,scale/shift 用来在同一框里挪出「云 + 降水」的版式。
 */
private fun DrawScope.drawCloud(
    color: Color,
    scale: Float = 1f,
    shiftX: Float = 0f,
    shiftY: Float = 0f,
) {
    fun x(v: Float) = size.width * (0.5f + (v - 0.5f) * scale + shiftX)
    fun y(v: Float) = size.height * (0.5f + (v - 0.5f) * scale + shiftY)
    fun r(v: Float) = size.width * v * scale
    drawCircle(color, r(0.19f), Offset(x(0.32f), y(0.58f)))
    drawCircle(color, r(0.25f), Offset(x(0.50f), y(0.42f)))
    drawCircle(color, r(0.19f), Offset(x(0.70f), y(0.58f)))
    drawRoundRect(
        color = color,
        topLeft = Offset(x(0.32f), y(0.58f)),
        size = Size(x(0.70f) - x(0.32f), y(0.78f) - y(0.58f)),
        cornerRadius = CornerRadius(r(0.09f)),
    )
}

private fun DrawScope.drawSun(center: Offset, radius: Float, color: Color) {
    drawCircle(color, radius, center)
    repeat(8) { index ->
        val angle = Math.toRadians((index * 45).toDouble())
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        drawLine(
            color = color,
            start = Offset(center.x + dx * radius * 1.55f, center.y + dy * radius * 1.55f),
            end = Offset(center.x + dx * radius * 2.2f, center.y + dy * radius * 2.2f),
            strokeWidth = radius * 0.32f,
            cap = StrokeCap.Round,
        )
    }
}

/** 云下的三条雨丝(only != null 时只画第 only 条,给雨夹雪复用)。 */
private fun DrawScope.drawDrops(color: Color, only: Int? = null) {
    listOf(0.34f, 0.52f, 0.70f).forEachIndexed { index, x ->
        if (only != null && index != only) return@forEachIndexed
        drawLine(
            color = color,
            start = Offset(size.width * (x + 0.045f), size.height * 0.74f),
            end = Offset(size.width * (x - 0.015f), size.height * 0.93f),
            strokeWidth = size.width * 0.055f,
            cap = StrokeCap.Round,
        )
    }
}

/** 云下的雪粒,同样是实心点,和雨丝区分开。 */
private fun DrawScope.drawFlakes(color: Color, only: Int? = null) {
    listOf(0.34f, 0.52f, 0.70f).forEachIndexed { index, x ->
        if (only != null && index != only) return@forEachIndexed
        drawCircle(color, size.width * 0.036f, Offset(size.width * x, size.height * 0.84f))
    }
}

private fun DrawScope.drawBolt(color: Color) {
    val path = Path().apply {
        moveTo(size.width * 0.56f, size.height * 0.66f)
        lineTo(size.width * 0.45f, size.height * 0.84f)
        lineTo(size.width * 0.53f, size.height * 0.84f)
        lineTo(size.width * 0.44f, size.height * 0.98f)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = size.width * 0.055f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun DrawScope.drawFog(cloud: Color, lines: Color) {
    drawCloud(cloud, scale = 0.9f, shiftY = -0.16f)
    listOf(0.72f, 0.88f).forEach { y ->
        drawLine(
            color = lines,
            start = Offset(size.width * 0.24f, size.height * y),
            end = Offset(size.width * 0.76f, size.height * y),
            strokeWidth = size.width * 0.055f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawWind(ink: Color, soft: Color) {
    // 三条长短不一的横线,中间一条压深一档,避免和「雾」的两条线撞脸
    listOf(0.32f to 0.74f, 0.52f to 0.88f, 0.72f to 0.60f).forEachIndexed { index, (y, end) ->
        drawLine(
            color = if (index == 1) ink else soft,
            start = Offset(size.width * 0.14f, size.height * y),
            end = Offset(size.width * end, size.height * y),
            strokeWidth = size.width * 0.065f,
            cap = StrokeCap.Round,
        )
    }
}

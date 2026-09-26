package com.kxin.classtable.design

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * OKLCh:OKLab 的极坐标形式(L 明度 / C 彩度 / H 色相),感知上比 HSL 均匀得多。
 *
 * 用它的理由很实际:HSL 里「色相走了多少度」和「看起来差多少」不是一回事,在近白/近黑档位上
 * 尤其失真 —— 上一版写的是 `hsl(hue, 0.30, 0.955)`,那个位置能挤出来的彩度只有 0.01 上下,
 * 于是七天一屏看过去几乎全是白纸。OKLCh 把三件事拆开:定住明度与彩度、只动色相,两色之间的
 * 感知距离就只由色相决定,「等距色相」这才真的等于「等距色差」(实测相邻色差因此提高 2–6 倍)。
 *
 * 这里只做一件事:把 (L, C, H) 转成 sRGB。超出 sRGB 色域时**保住明度与色相、二分压低彩度**
 * —— 这是 Material 那套(HCT)在色域边界上的做法:宁可少一点彩度,也不要让颜色变暗或跑偏。
 * 课程淡彩的目标彩度就是按这条规则量出来的:挑在「整圈色相都还不出界」的上限之下,
 * 于是没有任何一个色相被裁剪,色相环上相邻两色的距离处处相等。
 */
internal object Oklch {

    /** 判定「在线性 sRGB 内」的容差:太小会把边界色误判出界,太大又留下明显超界的通道。 */
    private const val GamutEpsilon = 1e-4f

    /** 色域裁剪的二分次数:20 次已把彩度收敛到 1e-6 量级,再多也看不出来。 */
    private const val GamutSearchSteps = 20

    /** (L 0..1, C 0..0.4, 色相角度) → sRGB 颜色。 */
    fun toColor(l: Float, c: Float, hueDegrees: Float): Color {
        val lightness = l.coerceIn(0f, 1f)
        if (lightness >= 1f) return Color.White
        if (lightness <= 0f) return Color.Black
        val chroma = c.coerceAtLeast(0f)
        if (chroma <= 0f) return oklabToLinear(lightness, 0f, 0f).toSrgbColor()

        val radians = hueDegrees * (PI / 180.0)
        val a = (chroma * cos(radians)).toFloat()
        val b = (chroma * sin(radians)).toFloat()
        val scale = { ratio: Float -> oklabToLinear(lightness, a * ratio, b * ratio) }

        val direct = scale(1f)
        if (isInGamut(direct)) return direct.toSrgbColor()

        // 同比例缩小 a、b = 只降彩度,明度与色相原地不动
        var low = 0f
        var high = 1f
        repeat(GamutSearchSteps) {
            val mid = (low + high) / 2f
            if (isInGamut(scale(mid))) low = mid else high = mid
        }
        return scale(low).toSrgbColor()
    }

    /** OKLab → 线性 sRGB(Björn Ottosson 的标准矩阵)。 */
    private fun oklabToLinear(l: Float, a: Float, b: Float): FloatArray {
        val lp = l + 0.3963377774f * a + 0.2158037573f * b
        val mp = l - 0.1055613458f * a - 0.0638541728f * b
        val sp = l - 0.0894841775f * a - 1.2914855480f * b
        val lc = lp * lp * lp
        val mc = mp * mp * mp
        val sc = sp * sp * sp
        return floatArrayOf(
            4.0767416621f * lc - 3.3077115913f * mc + 0.2309699292f * sc,
            -1.2684380046f * lc + 2.6097574011f * mc - 0.3413193965f * sc,
            -0.0041960863f * lc - 0.7034186147f * mc + 1.7076147010f * sc,
        )
    }

    private fun isInGamut(linear: FloatArray): Boolean = linear.all {
        it >= -GamutEpsilon && it <= 1f + GamutEpsilon
    }

    /** 线性 sRGB → sRGB(伽马编码),并夹住可能因容差溢出的那点零头。 */
    private fun FloatArray.toSrgbColor(): Color = Color(
        linearToSrgb(this[0]).coerceIn(0f, 1f),
        linearToSrgb(this[1]).coerceIn(0f, 1f),
        linearToSrgb(this[2]).coerceIn(0f, 1f),
        1f,
    )

    private fun linearToSrgb(value: Float): Float =
        if (value <= 0.0031308f) value * 12.92f else 1.055f * value.pow(1f / 2.4f) - 0.055f
}

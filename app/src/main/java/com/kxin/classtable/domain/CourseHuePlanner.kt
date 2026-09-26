package com.kxin.classtable.domain

import com.kxin.classtable.domain.model.Course
import com.kxin.classtable.domain.model.CourseColorScheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 课程色相的分配计划。
 *
 * 自动配色**不再在渲染时按课名现算**,而是新建 / 导入时把色相钉在课程上(`Course.colorHue`):
 * 于是课表里任意两门课都拿不到同一个色相,而且以后增删课程也不会把已有课程的颜色带跑
 * —— 与「课程时刻一旦导入就钉死」是同一个约定。
 *
 * 只有还没钉过色相的老记录会回退到 [preferred](按课名现算),用户在设置里点一次
 * 「重新配色」即可给所有课程补上互不重复的色相。
 *
 * 色相以整数度保存(0..359),一定落在 [wheel] 这张**等距**色相环上。等距是刻意的:
 * 相邻两色在感知上的距离因此处处相同,不会出现「这一对分得开、那一对几乎一样」。
 */
object CourseHuePlanner {

    /** 色相环的起点:避开正红,整圈铺开时观感更均衡。 */
    private const val StartDegrees = 20f

    /** 判定「这个色相已被占用」的角度容差。色相环最密的一档(16 色)步长 22.5°,4° 不会误伤邻居。 */
    private const val MinGapDegrees = 4f

    /** 按课名取种子:同名课(一门课分多条记录时)同色,方便对上号。 */
    fun seedOf(course: Course): String = course.name.ifBlank { course.id }

    /** 该方案在色相环上铺开的色相(整数度,升序卷绕:末位可能小于首位)。 */
    fun wheel(scheme: CourseColorScheme): List<Int> {
        val count = scheme.hueCount
        return (0 until count).map { index ->
            (StartDegrees + (index + 0.5f) * (360f / count)).roundToInt().mod(360)
        }
    }

    /** 按课名派生的「偏好色相」:同名课永远同色。 */
    fun preferred(seed: String, scheme: CourseColorScheme): Int {
        val wheel = wheel(scheme)
        return wheel[Math.floorMod(seed.hashCode(), wheel.size)]
    }

    /**
     * 给 [seed] 挑一个不与 [taken] 重复的色相:取**离已有色相最远**的那个,
     * 让课表里相邻的几门课彼此最好分辨;并列时优先它自己的偏好色相
     * —— 于是只有一门课时,拿到的仍像是「这门课的颜色」。
     *
     * 色相环用尽(课程比可用色相还多)时退回偏好色相:此时已无法保证两两不同,
     * 不再挑一个看起来同样随机的值假装分开了。
     */
    fun next(seed: String, taken: Collection<Int>, scheme: CourseColorScheme): Int {
        val wheel = wheel(scheme)
        val preferred = preferred(seed, scheme)
        val free = wheel.filter { hue -> taken.none { hueDistance(hue, it) < MinGapDegrees } }
        if (free.isEmpty()) return preferred
        return free.maxWithOrNull(
            compareBy<Int>(
                { minDistanceTo(it, taken) },
                { if (it == preferred) 1 else 0 },
                { -hueDistance(it, preferred) },
                { -it },
            ),
        ) ?: preferred
    }

    /** 色相环上的最短角距(0..180)。 */
    fun hueDistance(a: Int, b: Int): Float {
        val raw = abs(a - b) % 360
        return minOf(raw, 360 - raw).toFloat()
    }

    /** 没有任何已占用色相时给满值,于是所有候选并列、由偏好色相决定 —— 与 [next] 的兜底一致。 */
    private fun minDistanceTo(hue: Int, taken: Collection<Int>): Float =
        taken.minOfOrNull { hueDistance(hue, it) } ?: 360f
}

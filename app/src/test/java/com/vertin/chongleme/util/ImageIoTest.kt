package com.vertin.chongleme.util

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [sampleSizeFor] 的边界测试。
 *
 * 表驱动：把「输入 → 期望因子」写成数据，避免为每个组合写一个方法，
 * 也让新增边界（比如某种畸形相机尺寸）只需要加一行。
 */
class ImageIoTest {

    private data class Case(
        val width: Int,
        val height: Int,
        val maxEdge: Int,
        val expected: Int,
        val why: String,
    )

    private val cases = listOf(
        // 已是目标尺寸 → 不动
        Case(1080, 1080, 1080, 1, "正方形正好等于目标边长"),
        Case(4000, 1080, 4000, 1, "最长边正好等于 maxEdge"),
        Case(640, 480, 1080, 1, "整体小于 maxEdge"),

        // 只超一个像素也要升档（⌈101/100⌉ = 2）
        Case(100, 100, 100, 1, "正好等于"),
        Case(101, 100, 100, 2, "超 1 像素 → 2"),
        Case(200, 100, 100, 2, "正好两倍"),
        Case(201, 100, 100, 4, "⌈201/100⌉=3 → 向上取 2 的幂 = 4"),

        // 正方形
        Case(1200, 1200, 1000, 2, "⌈1.2⌉=2"),
        Case(2200, 2200, 1000, 4, "⌈2.2⌉=3 → 4"),
        Case(500, 500, 100, 8, "⌈5⌉=5 → 8"),

        // 超长边：只看最长边，与另一边无关
        Case(100_000, 10, 100, 1024, "⌈1000⌉=1000 → 1024"),
        Case(8, 6000, 100, 64, "竖图，最长边是高"),
        Case(50_000, 50_000, 1, 65_536, "极端缩放因子"),

        // 防御式输入
        Case(4000, 3000, 0, 1, "maxEdge=0 退化为不降采样"),
        Case(4000, 3000, -1, 1, "maxEdge 为负"),
        Case(0, 0, 100, 1, "宽高为 0"),
        Case(-100, 500, 100, 1, "宽为负"),
        Case(500, -100, 100, 1, "高为负"),
    )

    @Test
    fun `采样因子表`() {
        val failures = cases.mapNotNull { case ->
            val actual = sampleSizeFor(case.width, case.height, case.maxEdge)
            if (actual == case.expected) null
            else "(${case.width}×${case.height}, maxEdge=${case.maxEdge}) " +
                "期望 ${case.expected} 实际 $actual —— ${case.why}"
        }
        assertTrue("表中有 ${failures.size} 条不符合预期：\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    /**
     * 属性断言：不枚举期望值，而是检查「必须成立的性质」。
     *
     * 三条性质合起来等于函数定义：
     * 1. 结果是 2 的幂（BitmapFactory 只在 2 的幂上保证整除路径）；
     * 2. 缩放后最长边**向上取整**也 ≤ maxEdge（四舍五入不会越界）；
     * 3. 再小一档就违反第 2 条（最小性：不能白白多降一档，那是在丢分辨率）。
     */
    @Test
    fun `任意输入下都是 2 的幂、压得住、且不多降一档`() {
        val dimensions = listOf(1, 2, 7, 100, 999, 1000, 1001, 4000, 12_345, 100_000)
        val edges = listOf(1, 50, 100, 480, 1080, 4096, 200_000)
        val failures = mutableListOf<String>()

        for (w in dimensions) {
            for (h in dimensions) {
                for (edge in edges) {
                    val sample = sampleSizeFor(w, h, edge)
                    val longest = maxOf(w, h)
                    val label = "($w×$h, maxEdge=$edge) → $sample"

                    if (sample < 1 || (sample and (sample - 1)) != 0) {
                        failures += "$label 不是 2 的幂"
                        continue
                    }
                    val ceilAfter = (longest.toLong() + sample - 1) / sample
                    if (ceilAfter > edge) failures += "$label 缩放后最长边 $ceilAfter 仍大于 maxEdge"
                    if (sample > 1) {
                        val smaller = sample / 2
                        val ceilSmaller = (longest.toLong() + smaller - 1) / smaller
                        if (ceilSmaller <= edge) failures += "$label 多降了一档（$smaller 就够）"
                    }
                }
            }
        }
        assertTrue("属性断言失败 ${failures.size} 条：\n${failures.take(20).joinToString("\n")}", failures.isEmpty())
    }

    /** 暴力对拍：用最笨的循环算出「期望因子」，与实现逐一对齐。 */
    @Test
    fun `与暴力实现逐一对拍`() {
        val failures = mutableListOf<String>()
        for (w in 1..800) {
            for (h in 1..800 step 97) {
                for (edge in listOf(1, 3, 64, 100, 360, 800)) {
                    val longest = maxOf(w, h).toLong()
                    var expected = 1L
                    while ((longest + expected - 1) / expected > edge) expected *= 2
                    val actual = sampleSizeFor(w, h, edge).toLong()
                    if (actual != expected) failures += "($w×$h, $edge) 期望 $expected 实际 $actual"
                }
            }
        }
        assertTrue("与暴力对拍有 ${failures.size} 条不一致：\n${failures.take(20).joinToString("\n")}", failures.isEmpty())
    }
}

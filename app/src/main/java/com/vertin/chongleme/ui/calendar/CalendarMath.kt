package com.vertin.chongleme.ui.calendar

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 日历页的**纯计算**部分：格子布局与热力档位。没有任何 Compose / Android 依赖，
 * 因此可以被 JVM 单测直接跑（[com.vertin.chongleme.data.stats.Stats] 也是纯函数，两边同源）。
 *
 * ## 热力档位是怎么定的
 *
 * 档位 = **当天条数本身，封顶 5**（见 [level]），而不是分位数、也不是对数刻度：
 *
 * - 这个应用里「一天几次」本来就是 1..5 量级的离散可数事实（强度域见
 *   `Intensity.MIN..MAX`），用户能心算，颜色应当直接对应那个数字；
 * - 分位数着色（把最深的 20% 涂满）会让同一张图在不同数据量下含义漂移：
 *   只记过 3 天的用户，那 3 天全是最深色，看起来像「天天很猛」——那是误导。
 *   本项目的立场是日历只陈述事实，不做相对排名；
 * - 封顶 5 是因为再往上加档肉眼已经分不出（暖橙在该底色上到 1.0 就到底了），
 *   而 5 档以上属于极少数日子，把它们压平不会丢失可读信息。
 *
 * 每档的不透明度见 [alpha]：`0.10 + 0.18 × 档位`，即 1 档 0.28 → 5 档 1.0。
 * 0 档刻意**不画暖橙**（返回 0），由 UI 给一层中性底色，于是「没记录」和
 * 「记了但很少」在视觉上不会被混为一谈。
 *
 * ## 与 Stats.heatmap 的口径一致性（见 [monthTotal] 与 CalendarScreen 的说明）
 *
 * 本文件只做「把 heatmap 的计数换算成档位」和「把月份换算成格子」两件事，
 * 从不自己判断某个时间戳属于哪一天——那是 `DayKey.of` 的唯一职责。
 */
object CalendarMath {

    /** 最深一档的含义：5 条及以上。 */
    const val MAX_LEVEL: Int = 5

    /** 当天条数 → 档位（0..[MAX_LEVEL]）。0 表示那天没有记录。 */
    fun level(count: Int): Int = when {
        count <= 0 -> 0
        count >= MAX_LEVEL -> MAX_LEVEL
        else -> count
    }

    /** 档位 → 暖橙的不透明度。0 档返回 0，调用方不该拿它画暖色。 */
    fun alpha(level: Int): Float =
        if (level <= 0) 0f else (BASE_ALPHA + STEP_ALPHA * level).coerceAtMost(1f)

    /**
     * 把「相对本月偏移几个月」换算成那个月的 1 号。
     *
     * 用 `withDayOfMonth(1).plusMonths()` 而不是 `YearMonth`：先把日子压到 1 号，
     * 「1月31日 + 1 个月」这种边界就不会走到 `plusMonths` 的夹取规则上，
     * 结果永远是干净的一个整月。
     */
    fun monthOf(anchor: LocalDate, offsetMonths: Int): LocalDate =
        anchor.withDayOfMonth(1).plusMonths(offsetMonths.toLong())

    /**
     * 一个月的日历格子：**周一起算**，行优先，空位是 null。
     *
     * 为什么是周一：`Stats.frequency` 的「本周」用的是 ISO 周（周一起算，
     * 见其 `TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)`）。表头若用周日开头，
     * 用户会看到「本周次数」和日历第一列对不上——同一个界面里两套周起点是最典型的自相矛盾。
     *
     * 行数按需给出（5 行或 6 行），末尾补齐到整周。
     */
    fun monthRows(month: LocalDate): List<List<LocalDate?>> {
        val first = month.withDayOfMonth(1)
        val leading = first.dayOfWeek.value - DayOfWeek.MONDAY.value // 周一 = 0 .. 周日 = 6
        val length = first.lengthOfMonth()

        val cells = ArrayList<LocalDate?>(leading + length + 6)
        repeat(leading) { cells.add(null) }
        for (day in 1..length) cells.add(first.withDayOfMonth(day))
        while (cells.size % 7 != 0) cells.add(null)

        return cells.chunked(7)
    }

    /**
     * 某个月的条数合计：只累加 [heat] 里落在该月的键。
     *
     * 这个数**必须**从 heatmap 里算，而不是取 `Frequency.thisMonth`：
     * 后者只认「今天所在的那个月」，用户翻到 8 月时它给的是 10 月的数字，
     * 会和上面那张 8 月的格子直接冲突。日历上的每个数字都只跟格子走同一份数据。
     */
    fun monthTotal(heat: Map<LocalDate, Int>, month: LocalDate): Int =
        heat.entries.sumOf { (date, count) ->
            if (date.year == month.year && date.monthValue == month.monthValue) count else 0
        }

    private const val BASE_ALPHA = 0.10f
    private const val STEP_ALPHA = 0.18f
}

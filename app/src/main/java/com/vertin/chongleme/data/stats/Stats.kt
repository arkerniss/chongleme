package com.vertin.chongleme.data.stats

import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.util.DayKey
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 频次面板要的一组数字。契约冻结（T3 按字段名读取）。
 *
 * 字段的可空性就是语义：
 * - [avgDurationMin] 为 null = 「没有任何一条记录填过时长」，而不是 0 分钟；
 * - [longestGapDays] 为 null = 「只有一天有记录」，最长江无法定义。
 * 这两种 null 让 UI 能显示「—」而不是显示一个骗人的 0。
 */
data class Frequency(
    val thisWeek: Int,
    val thisMonth: Int,
    val avgPerWeek: Double,
    val avgDurationMin: Double?,
    val longestGapDays: Int?,
    val intensityCounts: Map<Int, Int>,
)

/**
 * 全部统计入口。**纯函数**：不读系统时钟、不碰 Android API、不改入参。
 *
 * 时间基准由参数传入：`today: LocalDate` 是「今天」，[Entry.occurredAt] 是数据。
 * 唯一的环境依赖是「时间戳落在哪一天」这个换算，它走 [DayKey] 的默认时区
 * （`ZoneId.systemDefault()`），可被测试用 `TimeZone.setDefault` 固定住。
 *
 * 全文件一律按「日历格子」而不是「物理时长」思考：所有跨天计算都先归到 `LocalDate`
 * 再去算天数差，因此夏令时切换日不会凭空多出或少掉一天。
 */
object Stats {

    /**
     * 当前连长：从 [today] 往前数，有多少天**连续**有记录。
     *
     * 判定规则（刻意如此）：
     * - 今天有记录 → 从今天开始往前数；
     * - 今天没记录但**昨天**有 → 从昨天开始往前数（宽容一天：夜里还没记而已，不该立刻归零）；
     * - 今天和昨天都没有 → 返回 0（断了两天以上，连长就真的断了）。
     *
     * 一天内记多条只算一天。
     */
    fun streakDays(entries: List<Entry>, today: LocalDate): Int {
        if (entries.isEmpty()) return 0
        val days = distinctDays(entries)

        val anchor = when {
            today in days -> today
            today.minusDays(1) in days -> today.minusDays(1)
            else -> return 0
        }

        var streak = 0
        var cursor = anchor
        while (cursor in days) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    /**
     * 历史最长连长（与今天无关，只看数据）。
     *
     * 例：1/1、1/2、1/5 → 2；例：只有 2024-02-29 → 1。
     * 空列表 → 0。
     */
    fun longestStreak(entries: List<Entry>): Int {
        if (entries.isEmpty()) return 0
        val days = distinctDays(entries).sorted()
        var best = 1
        var run = 1
        for (i in 1 until days.size) {
            run = if (DayKey.daysBetween(days[i - 1], days[i]) == 1L) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    /**
     * 一年热力图：`日期 → 当天条数`。
     *
     * 只保留 [year] 年，且**同一天多条会累加**（热力图要表达的是「那天冲了几次」，
     * 而不是「那天有没有冲」）。12 月 31 日的跨年边界由 `LocalDate.year` 判定，
     * 不经手毫秒，所以不存在「年边界漏一天」的经典 bug。
     *
     * 未来日期（手滑选了明天）也算进当年——热力图是日历视图，不是「到今天为止」的视图。
     * 需要「到今天为止」语义的是 [frequency]。
     */
    fun heatmap(entries: List<Entry>, year: Int): Map<LocalDate, Int> =
        entries.asSequence()
            .map { DayKey.of(it.occurredAt) }
            .filter { it.year == year }
            .groupingBy { it }
            .eachCount()

    /**
     * 频次面板。
     *
     * 统一规则：**只统计 `occurredAt` 不晚于 [today] 的记录**（未来时间戳一律排除），
     * 避免手滑选到明天就把统计撑大。这条规则对 [Frequency.thisWeek] /
     * [Frequency.thisMonth] / [Frequency.avgPerWeek] / [Frequency.avgDurationMin] /
     * [Frequency.intensityCounts] 全部生效，只有 [Heatmap][heatmap] 例外。
     *
     * 各字段定义：
     * - `thisWeek`：ISO 周（周一起算）窗口内条数；
     * - `thisMonth`：`today` 所在自然月内条数；
     * - `avgPerWeek`：总条数 ÷ 覆盖周数；覆盖周数 =（首条记录日 → today 的天数 + 1）÷ 7，
     *   且**不足一周按一周算**。分母含「今天」是刻意的：长期不记，平均值应该真的掉下来，
     *   而不是被一个越来越大的分母慢慢摊薄成一个漂亮的假象。
     * - `avgDurationMin`：所有非空时长的算术平均；没有一条填过时长 → null；
     * - `longestGapDays`：相邻两个「有记录的日子」之间的最大天数差；不足两天 → null。
     *   刻意**不含**「今天到上次记录」这段仍在进行中的空白：那个数字每天都变，
     *   放在「最长」这种历史性质的字段里会让数字自己缩水，语义会变成谎言。
     * - `intensityCounts`：强度 → 条数，只含出现过的强度，按强度升序（渲染稳定）。
     */
    fun frequency(entries: List<Entry>, today: LocalDate): Frequency {
        val past = entries.filter { !DayKey.of(it.occurredAt).isAfter(today) }
        val days = past.map { DayKey.of(it.occurredAt) }

        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekEndExclusive = weekStart.plusDays(7)
        val thisWeek = days.count { !it.isBefore(weekStart) && it.isBefore(weekEndExclusive) }

        val thisMonth = days.count { it.year == today.year && it.month == today.month }

        val avgPerWeek = if (past.isEmpty()) {
            0.0
        } else {
            val firstDay = days.min()
            val spanDays = DayKey.daysBetween(firstDay, today) + 1
            val weeks = maxOf(1.0, spanDays / 7.0)
            past.size / weeks
        }

        val durations = past.mapNotNull { it.durationMin }
        val avgDurationMin = if (durations.isEmpty()) null else durations.average()

        val distinct = days.distinct().sorted()
        val longestGapDays = if (distinct.size < 2) {
            null
        } else {
            (1 until distinct.size).maxOf { i ->
                DayKey.daysBetween(distinct[i - 1], distinct[i]).toInt()
            }
        }

        val intensityCounts = past.groupingBy { it.intensity }.eachCount().toSortedMap()

        return Frequency(
            thisWeek = thisWeek,
            thisMonth = thisMonth,
            avgPerWeek = avgPerWeek,
            avgDurationMin = avgDurationMin,
            longestGapDays = longestGapDays,
            intensityCounts = intensityCounts,
        )
    }

    /** 入参里出现过的所有「有记录的日子」，去重。一天多条只留一个。 */
    private fun distinctDays(entries: List<Entry>, zone: ZoneId = DayKey.zone()): Set<LocalDate> {
        val days = HashSet<LocalDate>(entries.size)
        for (entry in entries) days.add(DayKey.of(entry.occurredAt, zone))
        return days
    }
}

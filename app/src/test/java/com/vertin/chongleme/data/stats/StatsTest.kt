package com.vertin.chongleme.data.stats

import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.data.Mood
import com.vertin.chongleme.support.FixedTimeZoneRule
import com.vertin.chongleme.support.Fixtures
import com.vertin.chongleme.util.DayKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * [Stats] 的边界表。
 *
 * 默认时区固定在 Asia/Shanghai（UTC+8、无夏令时），所以「某天中午 12 点」的毫秒值是确定的。
 * 需要跨时区验证的用例见 [StatsTimeZoneTest]（那里把默认时区换成 UTC）。
 *
 * 覆盖清单（任务验收要求）：空列表、跨月、跨年、时区边界、闰日、连续中断、单日多条。
 */
class StatsTest {

    @get:Rule
    val timeZone: FixedTimeZoneRule = FixedTimeZoneRule("Asia/Shanghai")

    private val today: LocalDate = LocalDate.parse("2026-03-01")

    // ── 空列表 ────────────────────────────────────────────────────────────────

    @Test
    fun `空列表时所有统计都归零而不是崩溃`() {
        val empty = emptyList<Entry>()

        assertEquals(0, Stats.streakDays(empty, today))
        assertEquals(0, Stats.longestStreak(empty))
        assertTrue(Stats.heatmap(empty, 2026).isEmpty())

        val frequency = Stats.frequency(empty, today)
        assertEquals(0, frequency.thisWeek)
        assertEquals(0, frequency.thisMonth)
        assertEquals(0.0, frequency.avgPerWeek, 1e-9)
        assertNull("没有时长时应为 null，不能伪装成 0 分钟", frequency.avgDurationMin)
        assertNull("不足两天时最长江无法定义", frequency.longestGapDays)
        assertTrue(frequency.intensityCounts.isEmpty())
    }

    // ── 表驱动：当前连长 ──────────────────────────────────────────────────────

    private data class StreakCase(
        val name: String,
        val dates: List<String>,
        val today: String,
        val expected: Int,
    )

    private val streakCases = listOf(
        StreakCase("空列表", emptyList(), "2026-03-01", 0),
        StreakCase("只有今天一条", listOf("2026-03-01"), "2026-03-01", 1),
        StreakCase("今天没记、昨天记了 → 仍然算连着", listOf("2026-02-28"), "2026-03-01", 1),
        StreakCase("今天和昨天都没记 → 归零", listOf("2026-02-27"), "2026-03-01", 0),
        StreakCase("今天和昨天都有", listOf("2026-02-28", "2026-03-01"), "2026-03-01", 2),
        StreakCase("跨月连续", listOf("2026-02-27", "2026-02-28", "2026-03-01"), "2026-03-01", 3),
        StreakCase("跨年连续", listOf("2025-12-30", "2025-12-31", "2026-01-01"), "2026-01-01", 3),
        StreakCase("闰日连续", listOf("2024-02-28", "2024-02-29", "2024-03-01"), "2024-03-01", 3),
        StreakCase("单日多条只算一天", listOf("2026-03-01", "2026-03-01", "2026-03-01"), "2026-03-01", 1),
        StreakCase("断一天即重新起算", listOf("2026-02-26", "2026-02-27", "2026-03-01"), "2026-03-01", 1),
        StreakCase(
            "连续中断后从断点起算",
            listOf("2026-02-20", "2026-02-21", "2026-02-22", "2026-02-27", "2026-02-28", "2026-03-01"),
            "2026-03-01",
            3,
        ),
        StreakCase("未来日期不参与往前数", listOf("2026-03-01", "2026-03-05"), "2026-03-01", 1),
    )

    @Test
    fun `当前连长表`() {
        val failures = streakCases.mapNotNull { case ->
            val entries = Fixtures.entriesAt(*case.dates.toTypedArray())
            val actual = Stats.streakDays(entries, LocalDate.parse(case.today))
            if (actual == case.expected) null else "${case.name}：期望 ${case.expected}，实际 $actual"
        }
        assertTrue(
            "当前连长表有 ${failures.size} 条不符合预期：\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
    }

    // ── 表驱动：历史最长连长 ──────────────────────────────────────────────────

    private data class LongestCase(val name: String, val dates: List<String>, val expected: Int)

    private val longestCases = listOf(
        LongestCase("空列表", emptyList(), 0),
        LongestCase("单日多条", listOf("2026-03-01", "2026-03-01"), 1),
        LongestCase("连续中断", listOf("2026-01-01", "2026-01-02", "2026-01-05", "2026-01-06", "2026-01-07"), 3),
        LongestCase("跨月", listOf("2026-02-27", "2026-02-28", "2026-03-01"), 3),
        LongestCase("跨年", listOf("2025-12-30", "2025-12-31", "2026-01-01", "2026-01-02"), 4),
        LongestCase("闰日", listOf("2024-02-28", "2024-02-29", "2024-03-01"), 3),
        LongestCase("平年 2 月 28 日到 3 月 1 日只差一天", listOf("2023-02-28", "2023-03-01"), 2),
        LongestCase("全部断开", listOf("2026-01-01", "2026-01-10", "2026-02-20"), 1),
        LongestCase("与今天无关", listOf("2020-05-01", "2020-05-02"), 2),
    )

    @Test
    fun `历史最长连长表`() {
        val failures = longestCases.mapNotNull { case ->
            val actual = Stats.longestStreak(Fixtures.entriesAt(*case.dates.toTypedArray()))
            if (actual == case.expected) null else "${case.name}：期望 ${case.expected}，实际 $actual"
        }
        assertTrue(
            "历史最长连长表有 ${failures.size} 条不符合预期：\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
    }

    // ── 验收清单里逐个点名的场景（独立用例，便于在报告里逐个指认） ────────────

    @Test
    fun `跨月连续不中断`() {
        val entries = Fixtures.entriesAt("2026-02-27", "2026-02-28", "2026-03-01")
        assertEquals(3, Stats.streakDays(entries, LocalDate.parse("2026-03-01")))
        assertEquals(3, Stats.longestStreak(entries))
    }

    @Test
    fun `跨年连续不中断`() {
        val entries = Fixtures.entriesAt("2025-12-31", "2026-01-01", "2026-01-02")
        assertEquals(3, Stats.streakDays(entries, LocalDate.parse("2026-01-02")))
        assertEquals(3, Stats.longestStreak(entries))
    }

    @Test
    fun `闰日算作正常的一天`() {
        val leap = Fixtures.entriesAt("2024-02-28", "2024-02-29", "2024-03-01")
        assertEquals(3, Stats.longestStreak(leap))
        assertEquals(1, Stats.heatmap(leap, 2024)[LocalDate.parse("2024-02-29")])

        // 平年没有 2 月 29 日：2/28 与 3/1 本来就是相邻两天，连长必须是 2 而不是 3
        val common = Fixtures.entriesAt("2023-02-28", "2023-03-01")
        assertEquals(2, Stats.longestStreak(common))
    }

    @Test
    fun `单日多条只算一天但热力图累加`() {
        val entries = listOf(
            Fixtures.entry("2026-03-01", hour = 1),
            Fixtures.entry("2026-03-01", hour = 9),
            Fixtures.entry("2026-03-01", hour = 21),
        )
        assertEquals(1, Stats.streakDays(entries, today))
        assertEquals(1, Stats.longestStreak(entries))
        assertEquals(3, Stats.heatmap(entries, 2026)[LocalDate.parse("2026-03-01")])
        assertEquals(3, Stats.frequency(entries, today).thisMonth)
    }

    @Test
    fun `连续中断后重新起算`() {
        val entries = Fixtures.entriesAt("2026-02-20", "2026-02-21", "2026-02-22", "2026-02-27", "2026-02-28", "2026-03-01")
        assertEquals(3, Stats.streakDays(entries, today))
        assertEquals(3, Stats.longestStreak(entries))
        assertEquals(5, Stats.frequency(entries, today).longestGapDays)
    }

    @Test
    fun `时区边界决定归属日`() {
        // 上海 2026-01-01 00:30 == UTC 2025-12-31 16:30
        val instant = Fixtures.at("2026-01-01", 0, 30, Fixtures.TEST_ZONE)
        assertEquals(LocalDate.parse("2026-01-01"), DayKey.of(instant, Fixtures.TEST_ZONE))
        assertEquals(LocalDate.parse("2025-12-31"), DayKey.of(instant, ZoneId.of("UTC")))

        val entries = listOf(
            Entry(
                id = 1L,
                occurredAt = instant,
                durationMin = null,
                intensity = 3,
                mood = null,
                note = "",
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
        // 默认时区被规则固定为上海：这条记录属于 2026 年 1 月 1 日
        assertEquals(setOf(LocalDate.parse("2026-01-01")), Stats.heatmap(entries, 2026).keys)
        assertTrue("按 UTC 看它应该落在 2025 年", Stats.heatmap(entries, 2025).isEmpty())
        assertEquals(1, Stats.streakDays(entries, LocalDate.parse("2026-01-01")))
    }

    // ── 热力图 ────────────────────────────────────────────────────────────────

    @Test
    fun `热力图只保留指定年份且同日累加`() {
        val entries = Fixtures.entriesAt("2025-12-31", "2026-01-01", "2026-01-01", "2026-01-02", "2027-01-01")
        assertEquals(
            mapOf(LocalDate.parse("2026-01-01") to 2, LocalDate.parse("2026-01-02") to 1),
            Stats.heatmap(entries, 2026),
        )
        assertTrue(Stats.heatmap(entries, 2020).isEmpty())
        assertEquals(1, Stats.heatmap(entries, 2025)[LocalDate.parse("2025-12-31")])
        assertEquals(1, Stats.heatmap(entries, 2027)[LocalDate.parse("2027-01-01")])
    }

    @Test
    fun `热力图看得到年初与年末两端`() {
        val entries = Fixtures.entriesAt("2026-01-01", "2026-12-31", "2027-01-01")
        assertEquals(
            setOf(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")),
            Stats.heatmap(entries, 2026).keys,
        )
    }

    @Test
    fun `同一天的不同时刻归到同一个格子`() {
        val entries = listOf(
            Fixtures.entry("2026-03-01", hour = 0),
            Fixtures.entry("2026-03-01", hour = 12),
            Fixtures.entry("2026-03-01", hour = 23),
        )
        assertEquals(3, Stats.heatmap(entries, 2026)[LocalDate.parse("2026-03-01")])
    }

    @Test
    fun `未来日期也算进热力图`() {
        // 热力图是日历视图（手滑选了明天也该显示），而频次面板排除未来——两者刻意不同
        val entries = Fixtures.entriesAt("2026-12-31")
        assertEquals(1, Stats.heatmap(entries, 2026)[LocalDate.parse("2026-12-31")])
    }

    // ── 频次 ──────────────────────────────────────────────────────────────────

    @Test
    fun `本周按 ISO 周从周一起算`() {
        // 2026-03-05 是周四 → 本周 = 3/2(一) 至 3/8(日)
        // 2/28 与 3/1(日) 属于上一周；3/8 虽然在同一个 ISO 周里，但它是未来，按规则排除
        val entries = Fixtures.entriesAt("2026-02-28", "2026-03-01", "2026-03-02", "2026-03-04", "2026-03-05", "2026-03-08")
        val frequency = Stats.frequency(entries, LocalDate.parse("2026-03-05"))
        assertEquals(3, frequency.thisWeek)
    }

    @Test
    fun `本月不含上月也不含未来`() {
        val entries = Fixtures.entriesAt("2026-02-28", "2026-03-01", "2026-03-05", "2026-03-31")
        val frequency = Stats.frequency(entries, LocalDate.parse("2026-03-05"))
        assertEquals(2, frequency.thisMonth)
    }

    @Test
    fun `平均每周次数不足一周按一周算`() {
        val entries = Fixtures.entriesAt("2026-03-01", "2026-03-05")
        val frequency = Stats.frequency(entries, LocalDate.parse("2026-03-05"))
        assertEquals(2.0, frequency.avgPerWeek, 1e-9)
    }

    @Test
    fun `平均每周次数按覆盖周数摊薄`() {
        val entries = Fixtures.entriesAt("2026-03-01", "2026-03-15")
        val frequency = Stats.frequency(entries, LocalDate.parse("2026-03-15"))
        assertEquals(2.0 / (15.0 / 7.0), frequency.avgPerWeek, 1e-9)
    }

    @Test
    fun `长期不记时平均值真的下降`() {
        val entries = Fixtures.entriesAt("2026-03-01", "2026-03-02", "2026-03-03")
        val soon = Stats.frequency(entries, LocalDate.parse("2026-03-03")).avgPerWeek
        val later = Stats.frequency(entries, LocalDate.parse("2026-04-03")).avgPerWeek
        assertTrue("$later 应该小于 $soon", later < soon)
    }

    @Test
    fun `平均时长忽略没填时长的记录`() {
        val entries = listOf(
            Fixtures.entry("2026-03-01", durationMin = 10),
            Fixtures.entry("2026-03-02", durationMin = 20),
            Fixtures.entry("2026-03-03", durationMin = null),
        )
        val frequency = Stats.frequency(entries, LocalDate.parse("2026-03-03"))
        assertEquals(15.0, requireNotNull(frequency.avgDurationMin), 1e-9)
    }

    @Test
    fun `没有任何时长时平均时长为 null 而不是 0`() {
        val entries = Fixtures.entriesAt("2026-03-01", "2026-03-02")
        assertNull(Stats.frequency(entries, LocalDate.parse("2026-03-02")).avgDurationMin)
    }

    @Test
    fun `最长间隔只看两次记录之间`() {
        val entries = Fixtures.entriesAt("2026-03-01", "2026-03-05", "2026-03-06")
        // 今天是 6 月 1 日，但「上次记录到今天」这段空白不算——那个数字每天都在变
        val frequency = Stats.frequency(entries, LocalDate.parse("2026-06-01"))
        assertEquals(4, frequency.longestGapDays)
    }

    @Test
    fun `只有一天记录时最长间隔为 null`() {
        val entries = Fixtures.entriesAt("2026-03-01", "2026-03-01")
        assertNull(Stats.frequency(entries, LocalDate.parse("2026-03-01")).longestGapDays)
    }

    @Test
    fun `强度分布只含出现过的档位且按键升序`() {
        val entries = listOf(
            Fixtures.entry("2026-03-01", intensity = 5),
            Fixtures.entry("2026-03-01", intensity = 1),
            Fixtures.entry("2026-03-02", intensity = 3),
            Fixtures.entry("2026-03-02", intensity = 3),
        )
        val counts = Stats.frequency(entries, LocalDate.parse("2026-03-02")).intensityCounts
        assertEquals(mapOf(1 to 1, 3 to 2, 5 to 1), counts)
        assertEquals(listOf(1, 3, 5), counts.keys.toList())
    }

    @Test
    fun `未来时间的记录不计入频次`() {
        // 2026-03-02 是周一，3/3 在同一周内：没有「排除未来」这条规则的话 thisWeek 会是 1
        val entries = listOf(Fixtures.entry("2026-03-03", durationMin = 30, intensity = 4))
        val frequency = Stats.frequency(entries, LocalDate.parse("2026-03-02"))
        assertEquals(0, frequency.thisWeek)
        assertEquals(0, frequency.thisMonth)
        assertEquals(0.0, frequency.avgPerWeek, 1e-9)
        assertNull(frequency.avgDurationMin)
        assertTrue(frequency.intensityCounts.isEmpty())
    }

    // ── 纯度 ──────────────────────────────────────────────────────────────────

    @Test
    fun `统计是纯函数：同输入同输出且不改入参`() {
        val entries = listOf(
            Fixtures.entry("2026-03-01", durationMin = 10, mood = Mood.Calm),
            Fixtures.entry("2026-03-02", durationMin = 20, mood = Mood.Happy),
        )
        val snapshot = entries.toList()

        assertEquals(Stats.frequency(entries, today), Stats.frequency(entries, today))
        assertEquals(Stats.streakDays(entries, today), Stats.streakDays(entries, today))
        assertEquals(Stats.longestStreak(entries), Stats.longestStreak(entries))
        assertEquals(Stats.heatmap(entries, 2026), Stats.heatmap(entries, 2026))
        assertEquals(snapshot, entries)
    }
}

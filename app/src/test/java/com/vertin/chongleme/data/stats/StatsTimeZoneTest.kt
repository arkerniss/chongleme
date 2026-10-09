package com.vertin.chongleme.data.stats

import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.support.FixedTimeZoneRule
import com.vertin.chongleme.support.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * 时区边界：**同一个时间戳**，在默认时区不同的两个 JVM 里必须落到不同的日历格子。
 *
 * 这里把默认时区固定成 UTC，[Fixtures.entry] 仍按 Asia/Shanghai 造时间戳——
 * 于是「上海跨年那一刻」在 UTC 看来还是前一年的 12 月 31 日。
 * 这组用例是 `Stats` 里所有 `DayKey.of(...)` 行为的反向验证：
 * 如果哪天有人把换算改成硬编码 `+08:00` 或 UTC，这里会立刻变红。
 */
class StatsTimeZoneTest {

    @get:Rule
    val timeZone: FixedTimeZoneRule = FixedTimeZoneRule("UTC")

    /** 上海 2026-01-01 00:30 == UTC 2025-12-31 16:30。 */
    private val newYearMomentInShanghai: Long = Fixtures.at("2026-01-01", 0, 30, Fixtures.TEST_ZONE)

    private fun entryAt(millis: Long): Entry = Entry(
        id = 1L,
        occurredAt = millis,
        durationMin = 20,
        intensity = 3,
        mood = null,
        note = "",
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `默认时区为 UTC 时该记录属于 2025 年 12 月 31 日`() {
        val entries = listOf(entryAt(newYearMomentInShanghai))

        assertTrue("UTC 下它不在 2026 年", Stats.heatmap(entries, 2026).isEmpty())
        assertEquals(1, Stats.heatmap(entries, 2025)[LocalDate.parse("2025-12-31")])
    }

    @Test
    fun `默认时区为 UTC 时连长挂在 12 月 31 日而不是 1 月 1 日`() {
        val entries = listOf(entryAt(newYearMomentInShanghai))

        assertEquals(1, Stats.streakDays(entries, LocalDate.parse("2025-12-31")))
        // UTC 下这条记录落在「昨天」，按宽容一天的规则今天仍然算连着……
        assertEquals(1, Stats.streakDays(entries, LocalDate.parse("2026-01-01")))
        // ……但再往后一天就必须断（上海时区下它本该是 1 月 1 日，那时 1 月 2 日仍然连着）
        assertEquals(0, Stats.streakDays(entries, LocalDate.parse("2026-01-02")))
    }

    @Test
    fun `默认时区为 UTC 时本月统计落在 12 月`() {
        val entries = listOf(entryAt(newYearMomentInShanghai))

        assertEquals(1, Stats.frequency(entries, LocalDate.parse("2025-12-31")).thisMonth)
        assertEquals(0, Stats.frequency(entries, LocalDate.parse("2026-01-01")).thisMonth)
    }
}

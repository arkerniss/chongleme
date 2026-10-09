package com.vertin.chongleme.util

import com.vertin.chongleme.support.FixedTimeZoneRule
import com.vertin.chongleme.support.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * [DayKey] 的换算测试：毫秒 ↔ 日期、年范围、日历天数差、夏令时。
 *
 * 默认时区固定在 Asia/Shanghai（UTC+8，无夏令时），所以毫秒值都是确定的；
 * 需要夏令时的用例显式传入 `America/New_York`。
 */
class TimeTest {

    @get:Rule
    val timeZone: FixedTimeZoneRule = FixedTimeZoneRule("Asia/Shanghai")

    @Test
    fun `一天的边界是左闭右开且能往返`() {
        val day = LocalDate.parse("2026-03-01")
        val start = DayKey.startOfDay(day)
        val end = DayKey.endOfDayExclusive(day)

        assertEquals(Fixtures.at("2026-03-01", 0, 0), start)
        assertEquals(Fixtures.at("2026-03-02", 0, 0), end)

        assertEquals(day, DayKey.of(start))
        assertEquals(day, DayKey.of(end - 1))
        assertEquals(LocalDate.parse("2026-03-02"), DayKey.of(end))
    }

    @Test
    fun `日范围包含起点不包含终点`() {
        val range = DayKey.rangeOfDay(LocalDate.parse("2026-03-01"))
        assertTrue(range.contains(DayKey.startOfDay(LocalDate.parse("2026-03-01"))))
        assertFalse(range.contains(DayKey.endOfDayExclusive(LocalDate.parse("2026-03-01"))))
        assertTrue(range.contains(DayKey.endOfDayExclusive(LocalDate.parse("2026-03-01")) - 1))
        assertFalse(range.isEmpty)
    }

    @Test
    fun `年范围是左闭右开且闰年有 366 天`() {
        val leap = DayKey.rangeOfYear(2024)
        assertEquals(Fixtures.at("2024-01-01", 0, 0), leap.startInclusive)
        assertEquals(Fixtures.at("2025-01-01", 0, 0), leap.endExclusive)
        assertEquals(366L, (leap.endExclusive - leap.startInclusive) / 86_400_000L)

        val common = DayKey.rangeOfYear(2026)
        assertEquals(365L, (common.endExclusive - common.startInclusive) / 86_400_000L)
    }

    @Test
    fun `跨年边界不漏一毫秒`() {
        val y2026 = DayKey.rangeOfYear(2026)
        val lastMoment = DayKey.endOfDayExclusive(LocalDate.parse("2026-12-31")) - 1
        assertTrue("12 月 31 日最后一毫秒必须在 2026 年内", y2026.contains(lastMoment))
        assertFalse("次年 1 月 1 日 00:00 必须落在 2026 年外", y2026.contains(DayKey.startOfDay(LocalDate.parse("2027-01-01"))))
    }

    @Test
    fun `天数差按日历格数算而闰日确实存在`() {
        assertEquals(2L, DayKey.daysBetween(LocalDate.parse("2024-02-28"), LocalDate.parse("2024-03-01")))
        assertEquals(1L, DayKey.daysBetween(LocalDate.parse("2023-02-28"), LocalDate.parse("2023-03-01")))
        assertEquals(1L, DayKey.daysBetween(LocalDate.parse("2026-12-31"), LocalDate.parse("2027-01-01")))
        assertEquals(-1L, DayKey.daysBetween(LocalDate.parse("2026-01-01"), LocalDate.parse("2025-12-31")))
        assertEquals(0L, DayKey.daysBetween(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-01")))
    }

    @Test
    fun `夏令时开始那天只有 23 小时仍是同一天`() {
        val zone = ZoneId.of("America/New_York")
        val day = LocalDate.parse("2026-03-08") // 美国夏令时开始：02:00 → 03:00
        val start = DayKey.startOfDay(day, zone)
        val end = DayKey.endOfDayExclusive(day, zone)
        assertEquals(23 * 3_600_000L, end - start)
        assertEquals(day, DayKey.of(start, zone))
        assertEquals(day, DayKey.of(end - 1, zone))
    }

    @Test
    fun `夏令时结束那天有 25 小时`() {
        val zone = ZoneId.of("America/New_York")
        val day = LocalDate.parse("2026-11-01") // 美国夏令时结束：02:00 → 01:00
        val start = DayKey.startOfDay(day, zone)
        val end = DayKey.endOfDayExclusive(day, zone)
        assertEquals(25 * 3_600_000L, end - start)
    }

    @Test
    fun `同一个瞬间在不同时区属于不同的日期`() {
        // 上海 3 月 1 日 00:30 == UTC 2 月 28 日 16:30
        val instant = Fixtures.at("2026-03-01", 0, 30, Fixtures.TEST_ZONE)
        assertEquals(LocalDate.parse("2026-03-01"), DayKey.of(instant, Fixtures.TEST_ZONE))
        assertEquals(LocalDate.parse("2026-02-28"), DayKey.of(instant, ZoneId.of("UTC")))
        assertEquals(LocalDate.parse("2026-03-01"), DayKey.of(instant, ZoneId.of("Asia/Tokyo")))
    }

    @Test
    fun `同一天的判定与时刻无关`() {
        val morning = Fixtures.at("2026-03-01", 0, 0)
        val night = Fixtures.at("2026-03-01", 23, 59)
        val nextDay = Fixtures.at("2026-03-02", 0, 0)
        assertTrue(DayKey.sameDay(morning, night))
        assertFalse(DayKey.sameDay(night, nextDay))
    }

    @Test
    fun `今天键与默认时区一致`() {
        assertEquals(LocalDate.now(Fixtures.TEST_ZONE), todayKey())
    }
}

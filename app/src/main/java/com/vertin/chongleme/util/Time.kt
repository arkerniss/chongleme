package com.vertin.chongleme.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 半开区间的毫秒范围 `[startInclusive, endExclusive)`。
 *
 * 为什么不用 `LongRange`：`LongRange` 是闭区间，而「一天」的毫秒边界用闭区间表达
 * 会写出 `endInclusive = 下一天 00:00 - 1` 这种把毫秒精度焊死的东西。
 * 半开区间天然可加：今天 `[d0, d1)`、明天 `[d1, d2)`，不会重叠也不会漏一毫秒。
 */
data class MillisRange(val startInclusive: Long, val endExclusive: Long) {
    fun contains(millis: Long): Boolean = millis >= startInclusive && millis < endExclusive

    val isEmpty: Boolean get() = endExclusive <= startInclusive
}

/**
 * 日期键换算中心：`epochMillis ↔ LocalDate`。
 *
 * 为什么每个函数都带 `zone: ZoneId = zone()`：
 * 统计函数（[com.vertin.chongleme.data.stats.Stats]）的签名被冻结成「只吃 `LocalDate`」，
 * 于是「时间戳属于哪一天」这件事必须由一个明确的换算点负责，也就是这里。
 * 生产调用走默认时区；单元测试传入固定时区，就能在没有时区数据库波动的前提下
 * 断言跨时区边界的行为（例如 UTC+8 的 3 月 1 日 00:30 在 UTC 里其实是 2 月 28 日）。
 *
 * 这里的函数**不读系统时钟**：没有任何 `now()`，唯一的入口是调用方给的毫秒值或日期。
 * 唯一读的是系统默认时区，且可被参数覆盖。
 */
object DayKey {

    /** 当前默认时区。动态读取，方便测试改 `TimeZone.setDefault` 后立刻生效。 */
    fun zone(): ZoneId = ZoneId.systemDefault()

    /** 毫秒时间戳 → 它在该时区里的那一天。 */
    fun of(epochMillis: Long, zone: ZoneId = zone()): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    /** 某天 00:00:00.000（含）。夏令时切换日会返回当天第一个真实存在的瞬间。 */
    fun startOfDay(date: LocalDate, zone: ZoneId = zone()): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** 次日 00:00:00.000（不含），即当天的右开边界。 */
    fun endOfDayExclusive(date: LocalDate, zone: ZoneId = zone()): Long =
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun rangeOfDay(date: LocalDate, zone: ZoneId = zone()): MillisRange =
        MillisRange(startOfDay(date, zone), endOfDayExclusive(date, zone))

    /** 某年 1 月 1 日 00:00 到次年 1 月 1 日 00:00，左闭右开。 */
    fun rangeOfYear(year: Int, zone: ZoneId = zone()): MillisRange =
        MillisRange(startOfDay(LocalDate.of(year, 1, 1), zone), startOfDay(LocalDate.of(year + 1, 1, 1), zone))

    /**
     * `to - from` 的整天数（可负）。
     *
     * 用 [ChronoUnit.DAYS] 而不是 `(毫秒差 / 86_400_000)`：后者在跨夏令时切换时会少算或多算一天，
     * 而这里比较的是「日历上的格子」，不是「经过的物理时长」。
     */
    fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)

    /** 两个时间戳是否落在同一个日历天（默认时区）。 */
    fun sameDay(a: Long, b: Long, zone: ZoneId = zone()): Boolean = of(a, zone) == of(b, zone)
}

/** 今天的日期键（默认时区）。这是全项目**唯一**允许读时钟的地方。 */
fun todayKey(zone: ZoneId = DayKey.zone()): LocalDate = LocalDate.now(zone)

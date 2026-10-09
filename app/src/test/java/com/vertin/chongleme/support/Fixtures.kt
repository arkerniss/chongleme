package com.vertin.chongleme.support

import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.data.Mood
import com.vertin.chongleme.data.Photo
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 测试数据的地基。
 *
 * 所有「某天几点」都显式指定时区（默认 [TEST_ZONE] = Asia/Shanghai，UTC+8，无夏令时），
 * 这样时间戳是确定的：同一个字符串在任何机器上都得到同一个毫秒值，
 * 测试不会因为跑在哪个时区的 CI 上而变红。
 */
object Fixtures {

    /** 测试默认时区。中国没有夏令时，日期算术不会被 DST 污染。 */
    val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /** `2026-03-01` + 12:00 + [zone] → 毫秒时间戳。 */
    fun at(
        date: String,
        hour: Int = 12,
        minute: Int = 0,
        zone: ZoneId = TEST_ZONE,
    ): Long = LocalDate.parse(date)
        .atTime(LocalTime.of(hour, minute))
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

    /** 造一条记录；只需要给关注的字段，其余取合理默认值。 */
    fun entry(
        date: String,
        hour: Int = 12,
        durationMin: Int? = null,
        intensity: Int = 3,
        mood: Mood? = null,
        note: String = "",
        id: Long = 0L,
        createdAt: Long = 0L,
        updatedAt: Long = 0L,
        photos: List<Photo> = emptyList(),
        zone: ZoneId = TEST_ZONE,
    ): Entry = Entry(
        id = id,
        occurredAt = at(date, hour, zone = zone),
        durationMin = durationMin,
        intensity = intensity,
        mood = mood,
        note = note,
        createdAt = createdAt,
        updatedAt = updatedAt,
        photos = photos,
    )

    /** 一串日期 → 一串记录（每天一条，中午 12:00）。 */
    fun entriesAt(vararg dates: String): List<Entry> = dates.map { entry(it) }

    /** 一列日期字符串 → `List<LocalDate>`，让参数化表格读起来像日历。 */
    fun days(vararg dates: String): List<LocalDate> = dates.map { LocalDate.parse(it) }
}

package com.vertin.chongleme.ui.editor

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import com.vertin.chongleme.ui.dayLabel
import com.vertin.chongleme.util.DayKey
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 时间选择的收口处。
 *
 * 用系统的 [DatePickerDialog] / [TimePickerDialog] 而不是自绘：这个页面的主角是「写了什么」，
 * 日期时间是最不需要惊喜的一栏。系统选择器自带用户的习惯（地区顺序、24/12 小时制、
 * 无障碍朗读），自绘一套只会在这三件事上退步，还要多引依赖。
 *
 * 时刻始终以 epoch 毫秒在内部流转，只在「给用户看」和「用户选了」这两端做换算，
 * 换算点全在这个文件里——散出去就会变成「日期用 UTC、时间用本地」这种经典事故。
 */

private val ClockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 时间戳 → `HH:mm`。 */
internal fun clockLabelOf(occurredAt: Long): String =
    Instant.ofEpochMilli(occurredAt).atZone(DayKey.zone()).format(ClockFormatter)

/**
 * 时间戳 → 「今天 / 昨天 / 10月9日」，跨年时补上年份。
 *
 * 常规情形走 [dayLabel]（与时间轴、日历共用一套口径）；只有跨年时这里多说一句年份，
 * 因为「10月9日」在另一年里是误导。
 */
internal fun dateLabelOf(occurredAt: Long, today: LocalDate): String {
    val date = DayKey.of(occurredAt)
    return if (date.year == today.year) {
        dayLabel(date, today)
    } else {
        "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
    }
}

/** 弹系统的日期选择器。 */
internal fun openDatePicker(context: Context, occurredAt: Long, onPicked: (LocalDate) -> Unit) {
    val at = Instant.ofEpochMilli(occurredAt).atZone(DayKey.zone())
    val dialog = DatePickerDialog(
        context,
        { _, year, month, day -> onPicked(LocalDate.of(year, month + 1, day)) },
        at.year,
        at.monthValue - 1,
        at.dayOfMonth,
    )
    // 不给选未来的日子：记录写的是已经发生的事，而未来的格子会让日历热力图和连续天数
    // 出现一块谁都没法解释的亮斑。今天本身仍然可选。
    dialog.datePicker.maxDate = System.currentTimeMillis()
    dialog.show()
}

/** 弹系统的时间选择器。固定 24 小时制，与卡片上的 `HH:mm` 保持一致。 */
internal fun openTimePicker(context: Context, occurredAt: Long, onPicked: (LocalTime) -> Unit) {
    val at = Instant.ofEpochMilli(occurredAt).atZone(DayKey.zone())
    TimePickerDialog(
        context,
        { _, hour, minute -> onPicked(LocalTime.of(hour, minute)) },
        at.hour,
        at.minute,
        true,
    ).show()
}

/**
 * 把「只改日期」或「只改时间」合回一个时间戳。
 *
 * [date] / [time] 传 `null` 表示那一半不动。用 [ZonedDateTime.of] 而不是自己拼毫秒：
 * 夏令时切换那天有不存在和重复的时刻，让 java.time 去决定怎么落。
 */
internal fun mergeOccurredAt(
    occurredAt: Long,
    date: LocalDate? = null,
    time: LocalTime? = null,
): Long {
    val zone = DayKey.zone()
    val current = Instant.ofEpochMilli(occurredAt).atZone(zone)
    return ZonedDateTime.of(
        date ?: current.toLocalDate(),
        time ?: current.toLocalTime(),
        zone,
    ).toInstant().toEpochMilli()
}

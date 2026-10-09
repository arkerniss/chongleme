package com.vertin.chongleme.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.vertin.chongleme.ui.rememberToday
import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.data.stats.Frequency
import com.vertin.chongleme.data.stats.Stats
import com.vertin.chongleme.theme.Colour
import com.vertin.chongleme.ui.BottomBarReserve
import com.vertin.chongleme.ui.glass.GlassButton
import com.vertin.chongleme.ui.glass.GlassCard
import com.vertin.chongleme.util.todayKey
import java.time.LocalDate
import java.util.Locale

/**
 * 日历。
 *
 * 一屏回答两件事：「哪几天记过」（格子深浅）和「现在是什么节奏」（下面那几张数字卡）。
 * 不做打卡日历、不做「本月目标完成度」——这个本子没有目标，只有记录。
 *
 * ## 与 [Stats.heatmap] 的对齐（读代码时先看这三条）
 *
 * 1. **取值唯一入口**：[heat] 只由 `Stats.heatmap(entries, month.year)` 产出，
 *    本文件从不自己 `DayKey.of` 某个时间戳来数日子。于是「一天怎么算」这件事
 *    只有一个实现（`Stats.heatmap` 内部的 `DayKey.of`）。缺失的键就是 0 条，
 *    正好对应「那天没有记录」，不需要额外的兜底判断。
 * 2. **年份参数与格子范围严格同域**：格子只渲染显示月份的日期（`CalendarMath.monthRows`
 *    只产出该月的日），而查询用的 `year` 就是显示月份的年份，所以 heatmap 的
 *    `filter { it.year == year }` 不可能丢掉任何一个被渲染的格子。翻到 12 月时
 *    并不会去查下一年——因为格子里根本不画 1 月。
 * 3. **格子上的数字与格子下的数字同源**：下面那张卡的「本月/该月次数」用的是
 *    [CalendarMath.monthTotal]，它的输入就是这张 heatmap，不是
 *    `Frequency.thisMonth`（那个只认今天所在的月份，翻月后会与格子自相矛盾）。
 *
 * ## 视觉
 *
 * 42 个格子**不用玻璃**，只用 `background` 铺色：每格一层 backdrop 就是 42 次离屏渲染，
 * 在滚动列表里会直接掉帧。玻璃只用在整块月历、统计卡这些「一块」上。
 * 深浅编码见 [CalendarMath]，图例与格子共用同一个 [heatFill]，所以图例不会和实物走偏。
 */
@Composable
fun CalendarScreen(
    backdrop: Backdrop,
    entries: List<Entry>,
) {
    val today = rememberToday()
    var monthOffset by remember { mutableStateOf(0) }
    val month = remember(today, monthOffset) { CalendarMath.monthOf(today, monthOffset) }

    val heat = remember(entries, month.year) { Stats.heatmap(entries, month.year) }
    val monthTotal = remember(heat, month) { CalendarMath.monthTotal(heat, month) }
    val frequency = remember(entries, today) { Stats.frequency(entries, today) }
    val streak = remember(entries, today) { Stats.streakDays(entries, today) }
    val longest = remember(entries) { Stats.longestStreak(entries) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .displayCutoutPadding(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = BottomBarReserve + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            BasicText(
                text = "日历",
                modifier = Modifier.padding(start = 6.dp, top = 8.dp),
                style = TextStyle(
                    color = Colour.InkOnDark,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.5).sp,
                ),
            )
        }

        item(key = "month") {
            MonthSwitcher(
                backdrop = backdrop,
                month = month,
                offset = monthOffset,
                onOffsetChange = { monthOffset = it },
            )
        }

        item(key = "grid") {
            MonthGrid(
                backdrop = backdrop,
                month = month,
                heat = heat,
                today = today,
            )
        }

        item(key = "legend") { HeatLegend() }

        if (entries.isEmpty()) {
            item(key = "empty") {
                Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp)) {
                    BasicText(
                        text = "还没有记录",
                        style = TextStyle(color = Colour.InkMutedOnDark, fontSize = 15.sp),
                    )
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        text = "有记录的日子，格子颜色会变深。数字卡会在第一条记录之后出现。",
                        style = TextStyle(
                            color = Colour.InkMutedOnDark.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        ),
                    )
                }
            }
        } else {
            item(key = "stats") {
                StatsCard(
                    backdrop = backdrop,
                    month = month,
                    today = today,
                    monthTotal = monthTotal,
                    streak = streak,
                    longest = longest,
                    frequency = frequency,
                )
            }
            item(key = "intensity") {
                IntensityCard(backdrop = backdrop, frequency = frequency)
            }
        }
    }
}

/** 月份切换。左右是真实的控件，中间的月份数字用等宽字体，翻月时不会左右抖。 */
@Composable
private fun MonthSwitcher(
    backdrop: Backdrop,
    month: LocalDate,
    offset: Int,
    onOffsetChange: (Int) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassButton(
                onClick = { onOffsetChange(offset - 1) },
                backdrop = backdrop,
                modifier = Modifier.height(44.dp),
            ) {
                BasicText(
                    text = "上月",
                    style = TextStyle(color = Colour.InkOnDark, fontSize = 14.sp),
                )
            }

            BasicText(
                text = "${month.year}年${month.monthValue}月",
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    color = Colour.InkOnDark,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                ),
            )

            GlassButton(
                onClick = { onOffsetChange(offset + 1) },
                backdrop = backdrop,
                modifier = Modifier.height(44.dp),
            ) {
                BasicText(
                    text = "下月",
                    style = TextStyle(color = Colour.InkOnDark, fontSize = 14.sp),
                )
            }
        }

        if (offset != 0) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                GlassButton(
                    onClick = { onOffsetChange(0) },
                    backdrop = backdrop,
                    modifier = Modifier.height(40.dp),
                ) {
                    BasicText(
                        text = "回到本月",
                        style = TextStyle(color = Colour.InkMutedOnDark, fontSize = 13.sp),
                    )
                }
            }
        }
    }
}

/** 月历本体。表头在玻璃卡里，和格子共用同一套列宽。 */
@Composable
private fun MonthGrid(
    backdrop: Backdrop,
    month: LocalDate,
    heat: Map<LocalDate, Int>,
    today: LocalDate,
) {
    val rows = remember(month) { CalendarMath.monthRows(month) }
    val cellShape = remember { RoundedCornerShape(10.dp) }

    GlassCard(
        backdrop = backdrop,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(10.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            WEEKDAY_LABELS.forEach { label ->
                BasicText(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = TextStyle(
                        color = Colour.InkMutedOnDark,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { date ->
                        DayCell(
                            date = date,
                            count = if (date == null) 0 else heat[date] ?: 0,
                            isToday = date == today,
                            shape = cellShape,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单日格子。
 *
 * 0 条不是「空白」而是一层极淡的中性底色：格子必须在，否则整个月会看起来缺了几块，
 * 而「缺块」和「那天没记」在日历上是两件事。
 */
@Composable
private fun DayCell(
    date: LocalDate?,
    count: Int,
    isToday: Boolean,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val level = CalendarMath.level(count)

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(
                color = if (date == null) Color.Transparent else heatFill(level),
                shape = shape,
            )
            .then(
                if (isToday) Modifier.border(1.dp, Colour.InkOnDark.copy(alpha = 0.5f), shape)
                else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (date != null) {
            BasicText(
                text = date.dayOfMonth.toString(),
                style = TextStyle(
                    color = heatInk(level),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (level > 0) FontWeight.Medium else FontWeight.Normal,
                ),
            )
        }
    }
}

/**
 * 图例。和格子共用 [heatFill]，所以「图例说这是 1 条」和格子实际画出来的颜色永远一致。
 *
 * 直接写在壁纸上（不套玻璃）：它很小，再套一层玻璃只会多一次离屏渲染而不增加信息。
 */
@Composable
private fun HeatLegend() {
    val swatchShape = remember { RoundedCornerShape(5.dp) }

    Column(Modifier.padding(horizontal = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (level in 0..CalendarMath.MAX_LEVEL) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(swatchShape)
                            .background(heatFill(level), swatchShape)
                    )
                    Spacer(Modifier.height(3.dp))
                    BasicText(
                        text = if (level == CalendarMath.MAX_LEVEL) "${CalendarMath.MAX_LEVEL}+" else level.toString(),
                        style = TextStyle(
                            color = Colour.InkMutedOnDark,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        BasicText(
            text = "颜色越深，那天的记录条数越多；没记录的日子也留着一格底色。",
            style = TextStyle(
                color = Colour.InkMutedOnDark.copy(alpha = 0.75f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
            ),
        )
    }
}

/**
 * 数字卡。
 *
 * 「—」不是占位符，是 [Frequency] 契约里的真实语义：没有一条记录填过时长时，
 * 平均值没有定义；只有一天有记录时，最长江无法计算。下面的小字把这两件事说清楚，
 * 免得用户以为界面坏了。
 */
@Composable
private fun StatsCard(
    backdrop: Backdrop,
    month: LocalDate,
    today: LocalDate,
    monthTotal: Int,
    streak: Int,
    longest: Int,
    frequency: Frequency,
) {
    val isCurrentMonth = month.year == today.year && month.monthValue == today.monthValue

    GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
        StatRow("当前连续", "$streak 天")
        StatRow("最长连续", "$longest 天")
        StatRow(if (isCurrentMonth) "本月次数" else "该月次数", "$monthTotal 次")
        StatRow("本周次数", "${frequency.thisWeek} 次")
        StatRow("平均每周（至今）", "${oneDecimal(frequency.avgPerWeek)} 次")
        StatRow("平均时长", frequency.avgDurationMin?.let { "${oneDecimal(it)} 分" } ?: "—")
        StatRow("最长间隔", frequency.longestGapDays?.let { "$it 天" } ?: "—")

        if (frequency.avgDurationMin == null || frequency.longestGapDays == null) {
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = "「—」表示这个数还算不出来：要么没有记录填过时长，要么只有一天有记录。",
                style = TextStyle(
                    color = Colour.InkMutedOnDark.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                ),
            )
        }
    }
}

/** 强度分布：只画真实出现过的强度。没出现过的档位不摆一条空槽，那会变成一排无意义的零。 */
@Composable
private fun IntensityCard(backdrop: Backdrop, frequency: Frequency) {
    GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
        BasicText(
            text = "强度分布",
            style = TextStyle(
                color = Colour.InkMutedOnDark,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
            ),
        )
        Spacer(Modifier.height(10.dp))

        val counts = frequency.intensityCounts
        if (counts.isEmpty()) {
            BasicText(
                text = "暂时没有可统计的记录。",
                style = TextStyle(color = Colour.InkMutedOnDark.copy(alpha = 0.7f), fontSize = 12.sp),
            )
        } else {
            val max = counts.values.maxOrNull() ?: 0
            counts.forEach { (intensity, count) ->
                IntensityBar(intensity = intensity, count = count, max = max)
            }
        }
    }
}

@Composable
private fun IntensityBar(intensity: Int, count: Int, max: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = intensity.toString(),
            modifier = Modifier.width(16.dp),
            style = TextStyle(
                color = Colour.InkMutedOnDark,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(Colour.IntensityOff)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(if (max <= 0) 0f else count.toFloat() / max)
                    .background(Colour.IntensityOn)
            )
        }

        BasicText(
            text = "$count 次",
            modifier = Modifier
                .width(56.dp)
                .padding(start = 10.dp),
            style = TextStyle(
                color = Colour.InkOnDark,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
            ),
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = Colour.InkMutedOnDark, fontSize = 14.sp),
        )
        Spacer(Modifier.weight(1f))
        BasicText(
            text = value,
            style = TextStyle(
                color = Colour.InkOnDark,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}

/**
 * 档位 → 底色。格子与图例共用这一个函数，图例因此不可能和实物走偏。
 *
 * 0 档返回一层极淡的中性白而不是暖橙：暖橙是「有记录」的语义色，
 * 给 0 档留一点点暖色会让「没记」和「记了一条」在暗屏上难以区分。
 */
private fun heatFill(level: Int): Color =
    if (level <= 0) {
        Color.White.copy(alpha = 0.03f)
    } else {
        Colour.IntensityOn.copy(alpha = CalendarMath.alpha(level))
    }

/**
 * 档位 → 数字颜色。
 *
 * 3 档及以下的白字对比度还有 5:1 以上；4 档起暖橙底已经够亮（白字掉到 3.5:1 以下），
 * 换成深墨色能拿回 5:1 以上。换色阈值放在 4 而不是「看起来差不多就行」，
 * 是因为这两种组合里各有一个是达标的。
 */
private fun heatInk(level: Int): Color = when {
    level >= 4 -> Colour.InkOnLight
    level >= 1 -> Colour.InkOnDark
    else -> Colour.InkMutedOnDark
}

/** 一位小数。用 `Locale.ROOT` 定死小数点，避免某些区域设置下变成逗号。 */
private fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

/** 周一起算，与 [Stats.frequency] 的 ISO 周口径一致。 */
private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

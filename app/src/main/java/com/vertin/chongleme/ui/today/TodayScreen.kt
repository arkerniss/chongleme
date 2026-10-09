package com.vertin.chongleme.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.data.EntryRepository
import com.vertin.chongleme.theme.Colour
import com.vertin.chongleme.theme.GlassTokens
import com.vertin.chongleme.theme.LocalGlassContentColor
import com.vertin.chongleme.ui.BottomBarReserve
import com.vertin.chongleme.ui.glass.GlassButton
import com.vertin.chongleme.ui.glass.GlassCard
import com.vertin.chongleme.util.DayKey

/**
 * 今天。
 *
 * 布局意图：首屏只回答一个问题——「今天记过了吗」。所以最上面是沙漏
 * （今日进度的可视化），紧接着是「记一笔」，然后才是今天的时间轴。
 * 统计、日历、徽章都在别的 tab，这里不抢戏。
 *
 * [entries] 是外壳给的全量快照；本屏只做筛选与展示，不承担任何写操作之外的逻辑。
 */
@Composable
fun TodayScreen(
    backdrop: Backdrop,
    repository: EntryRepository,
    entries: List<Entry>,
    onRecord: () -> Unit,
    onOpen: (Entry) -> Unit,
) {
    val today = remember { java.time.LocalDate.now() }
    val todayEntries = remember(entries, today) {
        entries.filter { DayKey.of(it.occurredAt) == today }
            .sortedByDescending { it.occurredAt }
    }
    val earlierEntries = remember(entries, today) {
        entries.filter { DayKey.of(it.occurredAt) != today }
            .sortedByDescending { it.occurredAt }
    }

    val todayMinutes = remember(todayEntries) {
        todayEntries.sumOf { it.durationMin ?: 0 }
    }

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
        item(key = "hero") {
            HeroPanel(
                backdrop = backdrop,
                todayCount = todayEntries.size,
                todayMinutes = todayMinutes,
            )
        }

        item(key = "record") {
            GlassButton(
                onClick = onRecord,
                backdrop = backdrop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                BasicText(
                    text = "记一笔",
                    style = TextStyle(
                        color = LocalGlassContentColor.current,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp,
                    ),
                )
            }
        }

        if (entries.isEmpty()) {
            item(key = "empty") {
                EmptyState()
            }
        }

        if (todayEntries.isNotEmpty()) {
            item(key = "today-header") {
                SectionLabel("今天")
            }
            items(todayEntries, key = { it.id }) { entry ->
                EntryCard(entry = entry, backdrop = backdrop, onOpen = { onOpen(entry) })
            }
        }

        if (earlierEntries.isNotEmpty()) {
            item(key = "earlier-header") {
                SectionLabel("更早")
            }
            items(earlierEntries, key = { it.id }) { entry ->
                EntryCard(entry = entry, backdrop = backdrop, onOpen = { onOpen(entry) })
            }
        }
    }
}

/**
 * 空状态。
 *
 * 刻意不给插画、不给「立即开始记录吧！」这类催促文案。
 * 一个刚打开的应用对用户没有任何亏欠，安静地说明它在那儿就够了。
 */
@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            text = "还没有记录",
            style = TextStyle(
                color = Colour.InkMutedOnDark,
                fontSize = 15.sp,
            ),
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            text = "上面那个按钮，随时",
            style = TextStyle(
                color = Colour.InkMutedOnDark.copy(alpha = 0.7f),
                fontSize = 13.sp,
            ),
        )
    }
}

/** 分组标题。用极淡的字重做分隔，而不是加一条分割线。 */
@Composable
private fun SectionLabel(text: String) {
    BasicText(
        text = text,
        modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 2.dp),
        style = TextStyle(
            color = Colour.InkMutedOnDark,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
        ),
    )
}

/** 标题栏：应用名 + 一句状态。状态文案克制，不评判。 */
@Composable
private fun TitleBlock() {
    Box(Modifier.fillMaxWidth()) {
        BasicText(
            text = "冲了吗",
            style = TextStyle(
                color = Colour.InkOnDark,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
            ),
        )
    }
}

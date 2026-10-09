package com.vertin.chongleme.ui.badges

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.RoundedRectangle
import com.vertin.chongleme.ui.rememberToday
import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.data.achievements.Achievements
import com.vertin.chongleme.data.achievements.LockedBadge
import com.vertin.chongleme.data.achievements.UnlockedBadge
import com.vertin.chongleme.theme.Colour
import com.vertin.chongleme.theme.LocalGlassTokens
import com.vertin.chongleme.ui.BottomBarReserve
import com.vertin.chongleme.ui.glass.GlassPanel
import com.vertin.chongleme.util.todayKey

/**
 * 徽章。
 *
 * 全部由 [Achievements.evaluate] 现场推导，本屏不存任何「已读 / 已庆祝」标记：
 * 规则本身就不落库（见 `Achievements` 的说明），删掉记录徽章会诚实地退回去，
 * 这里也就没有需要维护的第二份状态。
 *
 * 视觉上只有两种待遇，且都承载真实信息：
 * - **已解锁**：暖橙的浅填色 + 一圈 1dp 暖橙描边，底部写「已解锁」。
 *   颜色之外还有文字，红绿色觉异常的用户也能分辨状态。
 * - **未解锁**：普通玻璃，文字降一档对比度，底部是一条进度条 + `2/3` 这样的实数，
 *   条件文案始终可见——看不到条件的话，徽章就只是个谜语。
 *
 * 刻意不做的事：不为每枚徽章配图标（14 个图标只会变成一屏贴纸）、不做解锁动画、
 * 不做催促式的鼓励文案。
 */
@Composable
fun BadgesScreen(
    backdrop: Backdrop,
    entries: List<Entry>,
) {
    val today = rememberToday()
    val badges = remember(entries, today) { Achievements.evaluate(entries, today) }
    val unlockedCount = badges.count { it.isUnlocked }

    LazyVerticalGrid(
        // 一行两枚。徽章文案比相册的日期长得多，三列会挤成竖排。
        columns = GridCells.Fixed(2),
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
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "title") {
            Column(Modifier.padding(start = 6.dp, top = 8.dp, bottom = 4.dp)) {
                BasicText(
                    text = "徽章",
                    style = TextStyle(
                        color = Colour.InkOnDark,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.5).sp,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                BasicText(
                    text = "已解锁 $unlockedCount / ${Achievements.total}",
                    style = TextStyle(
                        color = Colour.InkMutedOnDark,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                if (entries.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    BasicText(
                        text = "还没有记录。徽章跟着记录走，不用手动领取。",
                        style = TextStyle(
                            color = Colour.InkMutedOnDark.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        ),
                    )
                }
            }
        }

        items(badges, key = { it.id }) { badge ->
            // sealed interface 的穷尽分支：新增一种状态时这里会编译不过，不会被悄悄漏掉。
            when (badge) {
                is UnlockedBadge -> BadgeTile(
                    backdrop = backdrop,
                    title = badge.title,
                    condition = badge.condition,
                    progress = null,
                )

                is LockedBadge -> BadgeTile(
                    backdrop = backdrop,
                    title = badge.title,
                    condition = badge.condition,
                    progress = badge.current to badge.target,
                )
            }
        }
    }
}

/**
 * 一枚徽章。[progress] 传 `null` 表示已解锁。
 *
 * 高度固定：徽章墙的价值之一是「一眼扫过去」，高度参差会让扫描变成逐格阅读。
 * 条件文案限两行，超出的部分省略——现有条件最长是「累计时长达到 600 分钟」，两行足够。
 */
@Composable
private fun BadgeTile(
    backdrop: Backdrop,
    title: String,
    condition: String,
    progress: Pair<Int, Int>?,
) {
    val tokens = LocalGlassTokens.current
    val shape = remember(tokens.cornerRadius) { RoundedRectangle(tokens.cornerRadius) }
    val unlocked = progress == null

    GlassPanel(
        backdrop = backdrop,
        modifier = Modifier
            .fillMaxWidth()
            .height(TILE_HEIGHT),
        shape = shape,
        // 关掉投影：14 个格子各带一个 GraphicsLayer 的投影，在这个暗底上换不来任何可读性。
        shadow = null,
    ) {
        if (unlocked) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Colour.IntensityOn.copy(alpha = 0.14f), shape)
                    .border(1.dp, Colour.IntensityOn.copy(alpha = 0.5f), shape)
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            BasicText(
                text = title,
                style = TextStyle(
                    color = if (unlocked) Colour.InkOnDark else Colour.InkMutedOnDark,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )

            Spacer(Modifier.height(6.dp))

            BasicText(
                text = condition,
                maxLines = 2,
                style = TextStyle(
                    color = if (unlocked) {
                        Colour.InkMutedOnDark
                    } else {
                        Colour.InkMutedOnDark.copy(alpha = 0.7f)
                    },
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                ),
            )

            Spacer(Modifier.weight(1f))

            if (progress == null) {
                BasicText(
                    text = "已解锁",
                    style = TextStyle(
                        color = Colour.IntensityOn,
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp,
                    ),
                )
            } else {
                val (current, target) = progress
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Colour.IntensityOff)
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressFraction(current, target))
                            .background(Colour.InkMutedOnDark.copy(alpha = 0.5f))
                    )
                }
                Spacer(Modifier.height(6.dp))
                BasicText(
                    text = "$current/$target",
                    style = TextStyle(
                        color = Colour.InkMutedOnDark.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }
    }
}

/** 进度比例。target 理论上不会是 0，真出现了也按 0 画，而不是抛一个除零。 */
private fun progressFraction(current: Int, target: Int): Float =
    if (target <= 0) 0f else (current.toFloat() / target).coerceIn(0f, 1f)

/** 固定高度，保证徽章墙是整齐的两列方格。 */
private val TILE_HEIGHT = 148.dp

package com.vertin.chongleme.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.kyant.backdrop.Backdrop
import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.data.Intensity
import com.vertin.chongleme.theme.Colour
import com.vertin.chongleme.ui.LocalPhotoStore
import com.vertin.chongleme.ui.displayName
import com.vertin.chongleme.ui.glass.GlassCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 时间轴上的一张记录卡片。
 *
 * 信息层级：时间最大 → 强度点阵次之 → 时长与心情再次 → 备注与配图最后。
 * 一行只讲一件事，因为扫一眼时间轴时用户的注意力只够抓一个锚点。
 */
@Composable
fun EntryCard(
    entry: Entry,
    backdrop: Backdrop,
    onOpen: () -> Unit,
) {
    GlassCard(
        backdrop = backdrop,
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = remember(entry.occurredAt) { timeOfDay(entry.occurredAt) },
                style = TextStyle(
                    color = Colour.InkOnDark,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                ),
            )

            Spacer(Modifier.width(10.dp))

            IntensityDots(entry.intensity)

            Spacer(Modifier.weight(1f))

            entry.durationMin?.let { minutes ->
                BasicText(
                    text = "${minutes}分",
                    style = TextStyle(
                        color = Colour.InkMutedOnDark,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }

        entry.mood?.let { mood ->
            Spacer(Modifier.height(8.dp))
            MoodChip(mood.displayName)
        }

        if (entry.note.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = entry.note.trim().lineSequence().first(),
                style = TextStyle(
                    color = Colour.InkMutedOnDark,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                ),
                maxLines = 1,
            )
        }

        if (entry.photos.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            PhotoStrip(entry)
        }
    }
}

/**
 * 强度：五个点。
 *
 * 用点阵而不是星星或进度条：点阵是不带价值判断的——「3」既不比「5」差，
 * 也不比「1」好。星级和进度条都隐含「越多越好」，那是在替用户下结论。
 */
@Composable
private fun IntensityDots(intensity: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Intensity.range.forEach { level ->
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (level <= intensity) Colour.IntensityOn
                        else Colour.IntensityOff
                    )
            )
        }
    }
}

/** 心情标签：一个低对比的胶囊。不配图标——五个心情配五个图标只会变成噪音。 */
@Composable
private fun MoodChip(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = Colour.InkMutedOnDark,
                fontSize = 11.sp,
                letterSpacing = 0.3.sp,
            ),
        )
    }
}

/**
 * 配图缩略图条。
 *
 * 用横向滚动而不是网格：一张卡片在列表里的高度必须可预期，
 * 否则滚动时整个时间轴会随着每张卡片的图片数忽高忽低。
 */
@Composable
private fun PhotoStrip(entry: Entry) {
    val store = LocalPhotoStore.current

    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(entry.photos, key = { it.id }) { photo ->
            AsyncImage(
                model = remember(photo.relPath) { store.resolve(photo.relPath) },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
        }
    }
}

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun timeOfDay(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(timeFormatter)

/** 供日历与相册复用：把时间戳格式化成 `HH:mm`。 */
fun formatTimeOfDay(epochMillis: Long): String = timeOfDay(epochMillis)

/** 供其它屏复用：把时间戳格式化成 `M月d日`。 */
fun formatMonthDay(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .let { "${it.monthValue}月${it.dayOfMonth}日" }

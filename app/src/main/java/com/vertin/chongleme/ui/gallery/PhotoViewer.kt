package com.vertin.chongleme.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.kyant.backdrop.Backdrop
import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.data.Photo
import com.vertin.chongleme.theme.Colour
import com.vertin.chongleme.ui.BottomBarReserve
import com.vertin.chongleme.ui.LocalPhotoStore
import com.vertin.chongleme.ui.glass.GlassButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 全屏查看器。
 *
 * 只用 `HorizontalPager`（foundation 自带）做左右翻页，缩放是自己写的手势循环：
 * 引一个 zoom 库会带来一整套图片加载/手势状态机，而这个页面需要的只是
 * 「双指缩放 + 拖动 + 双击还原」三件事。
 *
 * ## 手势为什么不是直接用 `detectTransformGestures`
 *
 * 那个现成的手势检测器**只要过了触摸阈值就会消费事件**，于是单指横扫也会被它吃掉，
 * pager 再也翻不了页。所以这里自己等事件：只有「两根手指以上」或「已经放大」时才消费。
 * 于是：
 * - 没放大时，单指横扫完全交给 pager（和普通相册一样顺）；
 * - 双指捏合随时可用（不必先双击放大）；
 * - 放大之后单指拖动就是平移图片，不再翻页（`userScrollEnabled` 也跟着关掉）。
 *
 * 位移的夹取放在 `graphicsLayer` 里做：那里拿得到图层的真实尺寸，
 * 于是「不许把图拖出屏幕」这件事不需要额外的布局测量或状态。
 */
@Composable
fun PhotoViewer(
    items: List<GalleryItem>,
    initialIndex: Int,
    backdrop: Backdrop,
    onClose: () -> Unit,
    onOpenEntry: (Entry) -> Unit,
) {
    if (items.isEmpty()) return

    val start = initialIndex.coerceIn(0, items.lastIndex)
    val pagerState = rememberPagerState(initialPage = start, pageCount = { items.size })

    // 哪一页正处于放大状态。-1 表示没有。
    // 用它来关掉 pager 的滑动：否则放大后单指拖动会和翻页抢同一个手势。
    var zoomedPage by remember { mutableStateOf(-1) }

    val page = pagerState.currentPage.coerceIn(0, items.lastIndex)
    val current = items[page]

    BackHandler(enabled = true, onBack = onClose)

    Box(
        Modifier
            .fillMaxSize()
            // 近乎不透明的遮罩：查看器里任何一点壁纸透出来都会干扰对图片本身的判断。
            .background(Color.Black.copy(alpha = 0.93f))
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = zoomedPage != page,
            key = { index -> items.getOrNull(index)?.photo?.id ?: index },
        ) { pageIndex ->
            ZoomablePhoto(
                photo = items[pageIndex].photo,
                onZoomChange = { zoomed -> zoomedPage = if (zoomed) pageIndex else -1 },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .displayCutoutPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = BottomBarReserve + 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = formatOccurredAt(current.entry.occurredAt),
                    style = TextStyle(
                        color = Colour.InkOnDark,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                Spacer(Modifier.weight(1f))
                BasicText(
                    text = "${page + 1} / ${items.size}",
                    style = TextStyle(
                        color = Colour.InkMutedOnDark,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }

            Spacer(Modifier.weight(1f))

            BasicText(
                text = "双击放大，双指缩放",
                modifier = Modifier.fillMaxWidth(),
                style = TextStyle(
                    color = Colour.InkMutedOnDark.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                ),
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassButton(
                    onClick = { onOpenEntry(current.entry) },
                    backdrop = backdrop,
                    modifier = Modifier.height(44.dp),
                ) {
                    BasicText(
                        text = "跳到这条记录",
                        style = TextStyle(
                            color = Colour.InkOnDark,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }

                GlassButton(
                    onClick = onClose,
                    backdrop = backdrop,
                    modifier = Modifier.height(44.dp),
                ) {
                    BasicText(
                        text = "关闭",
                        style = TextStyle(color = Colour.InkMutedOnDark, fontSize = 14.sp),
                    )
                }
            }
        }
    }
}

/**
 * 一页图片 + 它自己的缩放状态。
 *
 * 缩放状态按 `photo.id` remember：pager 回收页面后重新组合时，翻回来的图是原样大小，
 * 而不是停在别人调过的 2.5 倍上。
 */
@Composable
private fun ZoomablePhoto(
    photo: Photo,
    onZoomChange: (Boolean) -> Unit,
) {
    val store = LocalPhotoStore.current
    val file = remember(photo.relPath) { store.resolve(photo.relPath) }

    // 手势协程不会因为 onZoomChange 换了实例而重启，所以读最新的那一份。
    val notifyZoom = rememberUpdatedState(onZoomChange)

    var scale by remember(photo.id) { mutableStateOf(1f) }
    var offset by remember(photo.id) { mutableStateOf(Offset.Zero) }

    AsyncImage(
        model = file,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            // 双击：在 1x 与 2.5x 之间切换。2.5x 是「看清细节」又不用再拖太远的位置。
            .pointerInput(photo.id) {
                detectTapGestures(
                    onDoubleTap = {
                        val next = if (scale > 1f) 1f else 2.5f
                        scale = next
                        offset = Offset.Zero
                        notifyZoom.value(next > 1f)
                    }
                )
            }
            // 捏合与拖动。见文件头：只有多指或已放大时才消费事件。
            .pointerInput(photo.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2 || scale > 1f) {
                            val next = (scale * event.calculateZoom()).coerceIn(1f, MAX_SCALE)
                            scale = next
                            offset = if (next <= 1f) Offset.Zero else offset + event.calculatePan()
                            notifyZoom.value(next > 1f)
                            event.changes.forEach { if (it.pressed) it.consume() }
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                // 夹取按图层尺寸算：放大多少倍就允许多大的位移，图永远有一条边贴着屏幕。
                val maxX = size.width * (scale - 1f) / 2f
                val maxY = size.height * (scale - 1f) / 2f
                translationX = offset.x.coerceIn(-maxX, maxX)
                translationY = offset.y.coerceIn(-maxY, maxY)
            },
    )
}

/** 5 倍是手指能舒服控制的边界；再大就只剩像素块了。 */
private const val MAX_SCALE = 5f

private val occurredFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")

private fun formatOccurredAt(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(occurredFormatter)

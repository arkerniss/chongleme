package com.vertin.chongleme.ui.gallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.kyant.backdrop.Backdrop
import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.theme.Colour
import com.vertin.chongleme.ui.BottomBarReserve
import com.vertin.chongleme.ui.LocalPhotoStore
import com.vertin.chongleme.ui.glass.rememberGlassHaptics

/**
 * 相册。
 *
 * 只做一件事：把所有配图按时间倒序看一眼。不排序、不过滤、不打标签——
 * 那些都属于「记录」页的职责，相册是回看用的，操作越多越不像相册。
 *
 * 时间轴取 [Entry.occurredAt]（见 [galleryItems] 的说明），分块标签用年月。
 * 网格是**正方形裁切**而不是按原图比例排瀑布流：瀑布流每格的加载高度不同，
 * 滚动时整页会持续抖动，而且用户在这个页面要的是「扫一眼」，不是逐张比较比例。
 *
 * 点开进全屏查看器（自绘 pager，不引第三方 zoom 库），查看器里可以跳回所属记录。
 * 本屏不写任何数据：[onOpen] 只是把意图交回外壳。
 */
@Composable
fun GalleryScreen(
    backdrop: Backdrop,
    entries: List<Entry>,
    onOpen: (Entry) -> Unit,
) {
    val items = remember(entries) { galleryItems(entries) }
    val sections = remember(items) { gallerySections(items) }

    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    // 越界的下标直接当没打开：数据被删掉时查看器自己会退场，不需要额外的清理逻辑。
    val openIndex = viewerIndex?.takeIf { it in items.indices }

    Box(Modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            EmptyGallery()
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(COLUMNS),
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
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "title") {
                    TitleBlock(photoCount = items.size)
                }

                sections.forEach { section ->
                    item(
                        span = { GridItemSpan(maxLineSpan) },
                        key = "month-${section.year}-${section.month}",
                    ) {
                        MonthLabel(section)
                    }

                    items(section.items, key = { it.photo.id }) { item ->
                        PhotoCell(
                            item = item,
                            onClick = { viewerIndex = item.index },
                        )
                    }
                }
            }
        }

        if (openIndex != null) {
            PhotoViewer(
                items = items,
                initialIndex = openIndex,
                backdrop = backdrop,
                onClose = { viewerIndex = null },
                onOpenEntry = { entry ->
                    // 先收起查看器再交回外壳：否则编辑页回来时还压着一层全屏遮罩。
                    viewerIndex = null
                    onOpen(entry)
                },
            )
        }
    }
}

@Composable
private fun TitleBlock(photoCount: Int?) {
    Column(Modifier.padding(start = 6.dp, top = 8.dp, bottom = 4.dp)) {
        BasicText(
            text = "相册",
            style = TextStyle(
                color = Colour.InkOnDark,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
            ),
        )
        if (photoCount != null) {
            Spacer(Modifier.height(4.dp))
            BasicText(
                text = "$photoCount 张配图",
                style = TextStyle(
                    color = Colour.InkMutedOnDark,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
    }
}

/** 月份分隔。它承载的是真实信息（哪个月的图），所以有一点视觉重量，但不配图标。 */
@Composable
private fun MonthLabel(section: GallerySection) {
    BasicText(
        text = "${section.year}年${section.month}月",
        modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 2.dp),
        style = TextStyle(
            color = Colour.InkMutedOnDark,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            fontFamily = FontFamily.Monospace,
        ),
    )
}

/**
 * 空状态。
 *
 * 不画占位格子、不放「去拍一张」的引导：还没配图就是还没配图，
 * 说明清楚之后把页面交还给用户。
 */
@Composable
private fun EmptyGallery() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .displayCutoutPadding()
            .padding(horizontal = 16.dp),
    ) {
        TitleBlock(photoCount = null)

        Spacer(Modifier.height(28.dp))

        Column(Modifier.padding(horizontal = 6.dp)) {
            BasicText(
                text = "还没有配图",
                style = TextStyle(color = Colour.InkMutedOnDark, fontSize = 15.sp),
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                text = "记录里加的图会出现在这里，按事情发生的时间倒着排。",
                style = TextStyle(
                    color = Colour.InkMutedOnDark.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                ),
            )
        }
    }
}

/**
 * 一格配图。
 *
 * 用 `File` 直接喂给 coil（与时间轴卡片同一套做法）：路径拼接只发生在
 * [com.vertin.chongleme.data.PhotoStore.resolve] 一处，本屏不碰字符串路径。
 */
@Composable
private fun PhotoCell(item: GalleryItem, onClick: () -> Unit) {
    val store = LocalPhotoStore.current
    val haptics = rememberGlassHaptics()

    AsyncImage(
        model = remember(item.photo.relPath) { store.resolve(item.photo.relPath) },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button) {
                haptics.tap()
                onClick()
            },
    )
}

/** 三列。两列太大（一屏看不到几张），四列在 360dp 宽的机器上每格不到 70dp，看不清。 */
private const val COLUMNS = 3

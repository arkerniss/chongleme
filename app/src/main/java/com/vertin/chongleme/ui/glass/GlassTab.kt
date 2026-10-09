package com.vertin.chongleme.ui.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.vertin.chongleme.theme.LocalGlassContentColor
import com.vertin.chongleme.theme.LocalGlassTokens

/**
 * 标签栏在拖动时给单个标签的缩放系数。
 *
 * 用「提供 `() -> Float`」而不是「提供 Float」是刻意的：这是个每秒变几十次的值，
 * 直接提供数值会让整条标签栏每帧重组；提供取值的 lambda 之后，读它发生在
 * [Modifier.graphicsLayer] 的绘制阶段，只失效那一帧的绘制。
 */
internal val LocalGlassTabScale: ProvidableCompositionLocal<() -> Float> =
    staticCompositionLocalOf { { 1f } }

/**
 * 标签栏里的一个标签。
 *
 * 只能在 [GlassTabBar] 的 `content` 里调用：它靠 `RowScope.weight` 均分宽度，
 * 靠 [LocalGlassTabScale] 拿到拖动时的缩放。
 *
 * 改写自官方 catalog 的 `components/LiquidBottomTab.kt`（Apache-2.0）。相对蓝本加了两件事：
 *
 * 1. 未选中/选中的内容色不同。蓝本把内容色交给调用方，实际用起来每个标签都要自己判一次
 *    选中态，很容易漏掉某个标签没跟着变色。这里直接由 [selected] 决定。
 * 2. 补了触感。
 *
 * @param selected 是否是当前选中项。它同时驱动无障碍语义（`Role.Tab` + `Selected`）。
 */
@Composable
fun RowScope.GlassTab(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = LocalGlassTokens.current
    val tabScale = LocalGlassTabScale.current
    val haptics = rememberGlassHaptics()

    CompositionLocalProvider(
        LocalGlassContentColor provides
                if (selected) tokens.contentColor else tokens.contentMutedColor
    ) {
        Column(
            modifier
                .clip(Capsule())
                .selectable(selected = selected, role = Role.Tab) {
                    haptics.tap()
                    onClick()
                }
                .fillMaxHeight()
                .weight(1f)
                .graphicsLayer {
                    val scale = tabScale()
                    scaleX = scale
                    scaleY = scale
                },
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

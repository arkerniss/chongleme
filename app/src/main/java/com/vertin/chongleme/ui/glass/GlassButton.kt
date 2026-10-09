package com.vertin.chongleme.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.shapes.Capsule
import com.vertin.chongleme.theme.GlassRoles
import com.vertin.chongleme.theme.LocalGlassContentColor
import com.vertin.chongleme.theme.LocalGlassTokens
import com.vertin.chongleme.theme.applyGlassSurface
import com.vertin.chongleme.theme.effect
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 胶囊玻璃按钮。
 *
 * 改写自官方 catalog 的 `components/LiquidButton.kt`（Apache-2.0）。
 *
 * 效果参数按蓝本原样：`vibrancy()` + `blur(2.dp)` + `lens(12.dp, 24.dp)`——按钮是「薄片」，
 * 模糊刻意比面板轻得多，这样它才像贴在壁纸上的一层玻璃，而不是一块磨砂塑料。
 * 按下时按 4dp 缩放做形变，手指拖动时还会被轻微拉扯（[InteractiveHighlight.offset]）。
 *
 * API 上相对蓝本做了三处收敛：
 * - 去掉了 `isInteractive` 布尔：本项目所有按钮都走同一套按压反馈，不需要这个开关。
 * - 去掉了 `tint`（Hue 混合色）：视觉基调是低对比的夜间色，色相混合在这里只会引入噪声。
 * - 内容色由 `LocalGlassContentColor` 提供，插槽里不用手动传色。
 *
 * @param backdrop **必须**是应用里那唯一一个共享 backdrop（见 `Wallpaper`）。
 *   组件内部绝不会自己创建 backdrop——每多一层就多一次全屏离屏渲染。
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val tokens = LocalGlassTokens.current
    val haptics = rememberGlassHaptics()
    val animationScope = rememberCoroutineScope()
    val highlight = remember(animationScope, tokens.tier) {
        InteractiveHighlight(animationScope = animationScope, tier = tokens.tier)
    }
    val effect = tokens.effect(GlassRoles.Button)

    CompositionLocalProvider(
        LocalGlassContentColor provides if (enabled) tokens.contentColor else tokens.contentMutedColor
    ) {
        Row(
            modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = { applyGlassSurface(tokens, effect) },
                    layerBlock = {
                        val width = size.width
                        val height = size.height
                        if (width > 0f && height > 0f) {
                            val progress = highlight.pressProgress
                            val scale = lerp(1f, 1f + 4f.dp.toPx() / height, progress)

                            // 位移用 tanh 压缩：手指拖得再远，按钮跟手也是渐近的，
                            // 不会出现「拖出屏幕按钮也跟着飞走」的滑脱感。
                            val maxOffset = size.minDimension
                            val offset = highlight.offset
                            translationX = maxOffset * tanh(0.05f * offset.x / maxOffset)
                            translationY = maxOffset * tanh(0.05f * offset.y / maxOffset)

                            val maxDragScale = 4f.dp.toPx() / height
                            val angle = atan2(offset.y, offset.x)
                            scaleX = scale +
                                    maxDragScale * abs(cos(angle) * offset.x / size.maxDimension) *
                                    (width / height).coerceAtMost(1f)
                            scaleY = scale +
                                    maxDragScale * abs(sin(angle) * offset.y / size.maxDimension) *
                                    (height / width).coerceAtMost(1f)
                        }
                    },
                    onDrawSurface = { drawRect(tokens.surface) }
                )
                .clickable(enabled = enabled, role = Role.Button) {
                    haptics.tap()
                    onClick()
                }
                .then(if (enabled) highlight.modifier else Modifier)
                .then(if (enabled) highlight.gestureModifier else Modifier)
                .padding(horizontal = 20.dp)
                .defaultMinSize(minWidth = 64.dp, minHeight = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

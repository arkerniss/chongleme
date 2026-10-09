package com.vertin.chongleme.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.RoundedRectangle
import com.vertin.chongleme.theme.LocalGlassTokens

/**
 * 玻璃卡片：一块内容容器，圆角 24dp（token 可调到 24–28dp），
 * 效果沿用 [GlassPanel] 的面板量级（blur 14dp + 轻折射）。
 *
 * 与 [GlassPanel] 的分工：
 * - [GlassPanel] 是原语：无内边距、`Box` 插槽、给浮层和自定义容器用。
 * - [GlassCard] 是成品：有内边距、`Column` 插槽、带柔和投影、可选整卡点击。
 *
 * 它内部仍然是 [GlassPanel]，所以「玻璃怎么画」只有一处实现。
 *
 * @param onClick 传 `null`（默认）就是纯展示卡片；传了才变可点击。
 *   用可空 lambda 而不是 `clickable: Boolean` 标志，是为了让「我忘了传点击」这件事
 *   在类型上就写不出来——`onClick = null` 是明确的、看得见的。
 */
@Composable
fun GlassCard(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 20.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = LocalGlassTokens.current
    val shape = remember(tokens.cornerRadius) { RoundedRectangle(tokens.cornerRadius) }
    val haptics = rememberGlassHaptics()
    val animationScope = rememberCoroutineScope()
    val highlight = remember(animationScope, tokens.tier) {
        InteractiveHighlight(animationScope = animationScope, tier = tokens.tier)
    }

    GlassPanel(
        backdrop = backdrop,
        modifier = modifier,
        shape = shape
    ) {
        val interaction = if (onClick != null) {
            Modifier
                .clip(shape)
                .clickable(role = Role.Button) {
                    haptics.tap()
                    onClick()
                }
                // 触点柔光画在玻璃之上、内容之下：挂在 Column 上正好是这个顺序。
                .then(highlight.modifier)
                .then(highlight.gestureModifier)
        } else {
            Modifier
        }

        Column(
            interaction.padding(contentPadding),
            content = content
        )
    }
}

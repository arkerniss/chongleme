package com.vertin.chongleme.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedRectangle
import com.vertin.chongleme.theme.GlassEffect
import com.vertin.chongleme.theme.GlassRoles
import com.vertin.chongleme.theme.LocalGlassContentColor
import com.vertin.chongleme.theme.LocalGlassTokens
import com.vertin.chongleme.theme.applyGlassSurface
import com.vertin.chongleme.theme.effect

/**
 * 可供选用的投影参数。**默认不开**，需要浮起来的浮层（比如 [GlassDialog]）才显式传。
 *
 * 为什么默认不开：`ShadowNode` 一 attach 就会建一个 `GraphicsLayer`，
 * 每次绘制都要往离屏层里录一遍轮廓再模糊一次。而在这个夜间暗底上，
 * 8% 的黑色投影几乎看不出来——每个卡片白付一次离屏渲染换一个看不见的效果，不划算。
 * 卡片之间的层次由玻璃自身的边缘高光（`Highlight.Default`）和折射来交代，
 * 这也更接近「液体玻璃」本来的样子：靠折射分层，而不是靠投影。
 *
 * 真需要时按下面的量级传：半径约为控件高度的 1/3，颜色黑、alpha 别超过 0.25。
 */
val GlassPanelShadow: Shadow = Shadow(radius = 18.dp, color = Color.Black.copy(alpha = 0.08f))

/**
 * 玻璃面板：本项目所有玻璃容器的**原语**。[GlassCard]、[GlassDialog] 都是它加一层语义。
 *
 * 它本身不带内边距、不带点击、不带滚动——那些留给调用方在自己的内容插槽里做。
 * 这样做的原因是玻璃容器最常见的坑：库替调用方决定了内边距，调用方想要一个全出血的
 * 分隔线时只能靠负 margin 绕过去。
 *
 * 效果参数默认取 [GlassRoles.Panel]（blur 14dp + 轻折射 + 圆角 24dp），
 * 也就是官方 catalog 里没有的「面板」量级——它介于按钮的薄片与标签栏的厚片之间，
 * 由 [GlassTokens] 的基准值直接给出。
 *
 * @param backdrop **必须**是应用里那唯一一个共享 backdrop。组件内部绝不新建 backdrop。
 * @param shape 默认是按 token 圆角生成的 `RoundedRectangle`。注意
 *   折射（`lens()`）只认 `RoundedRectangularShape` 或 `CornerBasedShape`：
 *   传入任意自定义 `Shape` 会让折射被跳过（库会抛 `UnsupportedOperationException`，
 *   所以这里不接管，交给调用方自己保证）。
 * @param shadow 默认 `null`（不投影）。投影会额外占一个 `GraphicsLayer`；卡片会成批出现在
 *   列表里，逐张投影的代价远大于它在暗底上的可见度。需要时传 [GlassPanelShadow] 或自定义。
 */
@Composable
fun GlassPanel(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedRectangle(LocalGlassTokens.current.cornerRadius),
    effect: GlassEffect = LocalGlassTokens.current.effect(GlassRoles.Panel),
    shadow: Shadow? = null,
    innerShadow: InnerShadow? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val tokens = LocalGlassTokens.current

    Box(
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = { applyGlassSurface(tokens, effect) },
            // 只有真的要投影时才把 provider 交给库：库里 ShadowNode 一 attach 就会
            // 建一个 GraphicsLayer，传个「永远返回 null 的 lambda」等于白付一次离屏渲染。
            shadow = shadow?.let { s -> { s } },
            innerShadow = innerShadow?.let { s -> { s } },
            onDrawSurface = { drawRect(tokens.surface) }
        )
    ) {
        CompositionLocalProvider(LocalGlassContentColor provides tokens.contentColor) {
            content()
        }
    }
}

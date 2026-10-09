package com.vertin.chongleme.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop

/**
 * 背景层，同时也是液体玻璃的**折射源**。
 *
 * 两个关键决定：
 *
 * 1. **纯程序化绘制，不引任何图片资源。** 背景是「深夜渐变 + 三个柔和光斑」，
 *    不需要照片级质感，但必须有明暗变化——玻璃才有东西可折射。
 *
 * 2. **[backdrop] 由调用方创建并传进来。** 每多一层 backdrop 就多一次离屏渲染，
 *    直接反映在滚动与拖动的手感上。全应用只允许存在一个 `LayerBackdrop`
 *    （在根组合里创建），所有玻璃控件共享它。这个约束是硬性的，不是风格偏好。
 *
 * 层叠顺序与官方 catalog 一致：背景层带 `layerBackdrop` 作为兄弟节点，
 * 玻璃控件作为另一个兄弟节点采样它。
 */
@Composable
fun Wallpaper(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier
) {
    val spec = remember { WallpaperSpec.Dark }

    Box(
        modifier
            .fillMaxSize()
            .layerBackdrop(backdrop)
            .drawWithCache {
                val vertical = Brush.verticalGradient(
                    colors = listOf(spec.top, spec.bottom)
                )
                onDrawBehind {
                    drawRect(vertical)
                    spec.glows.forEach { glow -> drawGlow(glow) }
                }
            }
    )
}

/**
 * 背景的可调参数。抽成数据类是为了让「调参页」（仅 debug 构建包含）
 * 能实时改动它，并把选定的值固化回来。
 */
data class WallpaperSpec(
    val top: Color,
    val bottom: Color,
    val glows: List<Glow>
) {
    companion object {
        /**
         * 唯一的背景 spec。本项目只有深色主题，理由见 [GlassTokens.Dark] 的注释——
         * 简言之：文字色是硬编码的深色主题墨水，跟随系统浅色会得到不可读的界面。
         */
        val Dark = WallpaperSpec(
            top = Colour.BackgroundTop,
            bottom = Colour.BackgroundBottom,
            glows = listOf(
                // 暖斑在右上，刻意偏离视觉中心，避免做成「发光球居中」的俗套
                Glow(Colour.GlowWarm, centerX = 0.78f, centerY = 0.18f, radius = 0.62f, alpha = 0.10f),
                // 冷斑在左下，半径更大更淡，负责把画面撑开
                Glow(Colour.GlowCool, centerX = 0.18f, centerY = 0.72f, radius = 0.72f, alpha = 0.09f),
                // 第三个小暖斑压在下缘，让底部不至于空掉
                Glow(Colour.GlowWarm, centerX = 0.55f, centerY = 0.98f, radius = 0.48f, alpha = 0.06f)
            )
        )
    }
}

/**
 * 一个径向光斑。
 *
 * 位置与半径用「相对画布的比例」而不是绝对像素，这样同一份 spec 在手机、
 * 折叠屏展开态、平板上都成立，不必为每种尺寸重算。
 */
data class Glow(
    val color: Color,
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val alpha: Float
)

private fun DrawScope.drawGlow(glow: Glow) {
    val center = Offset(size.width * glow.centerX, size.height * glow.centerY)
    val radius = size.maxDimension * glow.radius
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                glow.color.copy(alpha = glow.alpha),
                glow.color.copy(alpha = 0f)
            ),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

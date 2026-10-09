package com.vertin.chongleme.theme

import androidx.compose.foundation.isSystemInDarkTheme
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
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 全应用唯一的背景层，同时也是液体玻璃的折射源。
 *
 * 两个关键决定：
 *
 * 1. **只做程序化绘制，不引任何图片资源。** 背景本身是「深夜渐变 + 三个柔和光斑」，
 *    它不需要是照片级的东西，但必须有明暗变化——玻璃才有东西可折射。
 *
 * 2. **整个应用只有这一个 LayerBackdrop。** 每多一层 backdrop 就多一次离屏渲染，
 *    直接反映在滚动与拖动的手感上。页面里的玻璃控件共享这一个图层，
 *    而不是各自再开一层。
 */
@Composable
fun Wallpaper(modifier: Modifier = Modifier) {
    val isDark = isSystemInDarkTheme()
    val spec = remember(isDark) { WallpaperSpec.forTheme(isDark) }
    val backdrop = rememberLayerBackdrop()

    Box(
        modifier
            .fillMaxSize()
            // layerBackdrop 把这一层的绘制结果录进 graphicsLayer，
            // 供所有玻璃控件按需采样。
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
        fun forTheme(isDark: Boolean): WallpaperSpec = if (isDark) {
            WallpaperSpec(
                top = Colour.BackgroundTop,
                bottom = Colour.BackgroundBottom,
                glows = listOf(
                    // 暖斑在右上，位置刻意偏离视觉中心，避免做成「发光球居中」的俗套
                    Glow(color = Colour.GlowWarm, centerX = 0.78f, centerY = 0.18f, radius = 0.62f, alpha = 0.10f),
                    // 冷斑在左下，半径更大、更淡，负责把画面撑开
                    Glow(color = Colour.GlowCool, centerX = 0.18f, centerY = 0.72f, radius = 0.72f, alpha = 0.09f),
                    // 第三个小暖斑压在下缘，让底部不至于空掉
                    Glow(color = Colour.GlowWarm, centerX = 0.55f, centerY = 0.98f, radius = 0.48f, alpha = 0.06f)
                )
            )
        } else {
            WallpaperSpec(
                top = Color(0xFFF7F8FA),
                bottom = Color(0xFFE8ECF2),
                glows = listOf(
                    Glow(color = Colour.GlowWarm, centerX = 0.80f, centerY = 0.16f, radius = 0.62f, alpha = 0.16f),
                    Glow(color = Colour.GlowCool, centerX = 0.16f, centerY = 0.74f, radius = 0.72f, alpha = 0.14f)
                )
            )
        }
    }
}

/**
 * 一个径向光斑。
 *
 * 位置与半径用「相对画布的比例」而不是绝对像素，这样同一个 spec 在手机、
 * 折叠屏展开态、平板上都成立，不需要为每种尺寸重算。
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

package com.vertin.chongleme.ui.today

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.vertin.chongleme.theme.Colour
import com.vertin.chongleme.ui.glass.GlassPanel

/**
 * 首页的主视觉：一只玻璃沙漏。
 *
 * 它是全应用**唯一**被允许「用力」的地方——其余界面刻意克制。选择沙漏而不是
 * 进度环或大号数字，是因为沙漏的语义正好是本应用的核心：时间在流，
 * 流掉了多少是可以被看见的，但不必被评判。
 *
 * 填充比例用 `n / (n + 3)` 而不是 `n / 上限`：后者需要先决定「一天几次算满」，
 * 那个数字无论取几都是在替用户下判断。这个式子只会渐近逼近满，永远不宣布「满了」。
 */
@Composable
fun HeroPanel(
    backdrop: Backdrop,
    todayCount: Int,
    todayMinutes: Int,
) {
    val targetFill = remember(todayCount) { fillFraction(todayCount) }
    val fill by animateFloatAsState(
        targetValue = targetFill,
        animationSpec = tween(durationMillis = 900),
        label = "hourglass-fill",
    )

    GlassPanel(
        backdrop = backdrop,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Hourglass(
                fill = fill,
                hasRecord = todayCount > 0,
                modifier = Modifier
                    .height(132.dp)
                    .fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            BasicText(
                text = headline(todayCount),
                style = TextStyle(
                    color = Colour.InkOnDark,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp,
                ),
            )

            if (todayMinutes > 0) {
                Spacer(Modifier.height(5.dp))
                BasicText(
                    text = "累计 $todayMinutes 分钟",
                    style = TextStyle(
                        color = Colour.InkMutedOnDark,
                        fontSize = 13.sp,
                        // 等宽字体族让数字宽度一致，数值跳动时文案不会左右抖
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }
    }
}

/**
 * 顶部文案。三档：还没记、记了一次、记了多次。
 *
 * 没有一个字在评价用户。「今天记过了」只是陈述，而「今天还没有记录」
 * 也比「今天还没开始哦」少一点催促感。
 */
private fun headline(count: Int): String = when (count) {
    0 -> "今天还没有记录"
    1 -> "今天记过了"
    else -> "今天记了 $count 次"
}

/**
 * 填充比例。渐近而不封顶，理由见 [HeroPanel] 的注释。
 */
private fun fillFraction(count: Int): Float {
    if (count <= 0) return 0f
    return count / (count + 3f)
}

/**
 * 沙漏本体。
 *
 * 画法：上下两个对称的三角形在中间收成细颈，顶部剩余的沙随 [fill] 下降、
 * 底部堆积的沙随之上升。玻璃面板已经提供了折射背景，所以这里只用
 * 纯色描边 + 暖橙填充，不再叠加任何效果——否则会和背后的折射打架。
 */
@Composable
private fun Hourglass(
    fill: Float,
    hasRecord: Boolean,
    modifier: Modifier = Modifier,
) {
    val sandColor = if (hasRecord) Colour.IntensityOn else Colour.InkMutedOnDark.copy(alpha = 0.5f)
    val frameColor = Colour.InkOnDark.copy(alpha = 0.85f)

    Canvas(modifier) {
        val w = size.width
        val h = size.height

        // 沙漏的实际尺寸：宽度不超过画布的三分之一，保持修长
        val glassW = minOf(w * 0.34f, h * 0.52f)
        val glassH = h * 0.78f
        val cx = w / 2f
        val top = (h - glassH) / 2f
        val bottom = top + glassH
        val left = cx - glassW / 2f
        val right = cx + glassW / 2f
        val midY = (top + bottom) / 2f

        // 上下横梁
        val beamH = 3f.dp.toPx()
        val beamOverhang = glassW * 0.14f
        drawRect(
            color = frameColor,
            topLeft = Offset(left - beamOverhang, top - beamH),
            size = androidx.compose.ui.geometry.Size(glassW + beamOverhang * 2, beamH),
        )
        drawRect(
            color = frameColor,
            topLeft = Offset(left - beamOverhang, bottom),
            size = androidx.compose.ui.geometry.Size(glassW + beamOverhang * 2, beamH),
        )

        // 沙漏外形：两条折线，中间在 x = cx 处收成一点
        val outline = Path().apply {
            moveTo(left, top)
            lineTo(cx, midY)
            lineTo(left, bottom)
            moveTo(right, top)
            lineTo(cx, midY)
            lineTo(right, bottom)
        }
        drawPath(outline, color = frameColor, style = Stroke(width = 1.6f.dp.toPx()))

        // 上半部：剩余的沙。fill 越大，剩余越少。
        val upperRemaining = 1f - fill
        if (upperRemaining > 0.001f) {
            drawUpperSand(
                left = left, right = right, top = top, midY = midY,
                remaining = upperRemaining, color = sandColor,
            )
        }

        // 下半部：已流下的沙，堆积成一个小丘
        if (fill > 0.001f) {
            drawLowerSand(
                left = left, right = right, midY = midY, bottom = bottom,
                accumulated = fill, color = sandColor,
            )
        }
    }
}

/** 上半部的沙：贴着上横梁的一层，按剩余量往下长。 */
private fun DrawScope.drawUpperSand(
    left: Float,
    right: Float,
    top: Float,
    midY: Float,
    remaining: Float,
    color: Color,
) {
    val sandBottom = top + (midY - top) * remaining
    // 该高度处的半宽：从顶部的 (right-left)/2 线性收到瓶颈处的 0
    val t = (sandBottom - top) / (midY - top)
    val halfWidth = (right - left) / 2f * (1f - t)
    val cx = (left + right) / 2f

    val path = Path().apply {
        moveTo(left, top)
        lineTo(right, top)
        lineTo(cx + halfWidth, sandBottom)
        lineTo(cx - halfWidth, sandBottom)
        close()
    }
    drawPath(path, color = color)
}

/** 下半部的沙：从瓶颈往下堆成小丘，堆到一定程度后铺满底部。 */
private fun DrawScope.drawLowerSand(
    left: Float,
    right: Float,
    midY: Float,
    bottom: Float,
    accumulated: Float,
    color: Color,
) {
    val cx = (left + right) / 2f
    val halfWidthAtBottom = (right - left) / 2f

    // 沙丘高度：前期长得快，后期贴底铺开
    val hillHeight = (bottom - midY) * accumulated
    val hillBaseY = bottom
    val hillTopY = bottom - hillHeight
    val t = 1f - (hillTopY - midY) / (bottom - midY)
    val halfWidth = halfWidthAtBottom * t.coerceIn(0f, 1f)

    val path = Path().apply {
        moveTo(cx - halfWidth, hillBaseY)
        lineTo(cx + halfWidth, hillBaseY)
        lineTo(cx, hillTopY)
        close()
    }
    drawPath(path, color = color)
}

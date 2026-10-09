package com.vertin.chongleme.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.input.pointer.pointerInput
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.vertin.chongleme.theme.GlassTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 手指底下的那团柔光。
 *
 * 玻璃控件按下时不只该「缩一下」，还该有一圈从触点扩散开的提亮——这是液体玻璃和普通
 * 半透明卡片手感上最容易被感知到的差别之一。
 *
 * 两个修饰符分开暴露，是为了让调用方决定挂在哪：
 * - [modifier] 负责画（挂在被按的节点上，画在内容下面）
 * - [gestureModifier] 负责跟踪手指（挂在能拿到触摸坐标的节点上）
 *
 * 改写自官方 catalog 的 `utils/InteractiveHighlight.kt`（Apache-2.0）。相对蓝本改了两处：
 *
 * 1. 手势检测换成 foundation 的 `detectDragGestures`（蓝本用的是 demo 专属的
 *    `inspectDragGestures`）。
 * 2. 增加了档位判断。蓝本只用 `isRuntimeShaderSupported()` 二选一，这里改成按 [GlassTier]：
 *    只有 [GlassTier.Refraction] 才构造 AGSL 着色器；其余档位走纯色平铺，**并且把提亮幅度
 *    压到更低**。低端档的表面色本身已经接近不透明（见 `GlassTokens.fallbackSurfaceColor`），
 *    再叠一层强提亮会把文字冲白，对比度直接不达标。
 */
class InteractiveHighlight(
    private val animationScope: CoroutineScope,
    private val tier: GlassTier = GlassTier.Refraction,
    private val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset }
) {

    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero

    val pressProgress: Float get() = pressProgressAnimation.value

    /** 手指相对按下点的位移。按压缩放的形变就靠它算出来。 */
    val offset: Offset get() = positionAnimation.value - startPosition

    private val shader = if (tier == GlassTier.Refraction) RuntimeShader(RADIAL_FALLOFF_SHADER) else null

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressProgressAnimation.value
        if (progress > 0f) {
            if (shader != null) {
                drawRect(Color.White.copy(alpha = 0.08f * progress), blendMode = BlendMode.Plus)
                shader.apply {
                    val center = position(size, positionAnimation.value)
                    setFloatUniform("size", size.width, size.height)
                    setColorUniform("color", Color.White.copy(alpha = 0.15f * progress))
                    setFloatUniform("radius", size.minDimension * 1.5f)
                    setFloatUniform(
                        "position",
                        center.x.coerceIn(0f, size.width),
                        center.y.coerceIn(0f, size.height)
                    )
                }
                drawRect(
                    ShaderBrush(shader.asComposeShader()),
                    blendMode = BlendMode.Plus
                )
            } else {
                // 低端档：只给一点点平铺提亮，够表示「按到了」即可，不能吃掉对比度。
                drawRect(Color.White.copy(alpha = 0.05f * progress), blendMode = BlendMode.Plus)
            }
        }

        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope, tier) {
        detectDragGestures(
            onDragStart = { down ->
                startPosition = down
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                    launch { positionAnimation.snapTo(startPosition) }
                }
            },
            onDragEnd = {
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                    launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                }
            },
            onDragCancel = {
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                    launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                }
            }
        ) { change, _ ->
            animationScope.launch { positionAnimation.snapTo(change.position) }
        }
    }
}

private const val RADIAL_FALLOFF_SHADER = """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}
"""

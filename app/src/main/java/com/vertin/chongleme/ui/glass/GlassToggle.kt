package com.vertin.chongleme.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.vertin.chongleme.theme.Colour
import com.vertin.chongleme.theme.GlassRoles
import com.vertin.chongleme.theme.GlassTier
import com.vertin.chongleme.theme.LocalGlassTokens
import com.vertin.chongleme.theme.applyGlassSurface
import com.vertin.chongleme.theme.effect
import kotlinx.coroutines.flow.collectLatest

/**
 * 玻璃开关。
 *
 * 改写自官方 catalog 的 `components/LiquidToggle.kt`（Apache-2.0）。
 *
 * 效果参数按蓝本原样：旋钮静息时 `blur(8.dp)`，按下去模糊让位给
 * `lens(5.dp, 10.dp, chromaticAberration = true)` + `Highlight.Ambient` + `Shadow` + `InnerShadow`。
 * 那个「按下去磨砂变透明、还能看到折射」的切换是这个组件最值得保留的一笔。
 *
 * 相对蓝本改了两处：
 *
 * 1. **蓝本整块开关没有任何点击处理**，`onSelect` 只在 `onDragStopped` 里回调，
 *    而 `detectDragGestures` 要越过 touch slop 才会触发——也就是说蓝本里「轻点一下开关」
 *    是没有任何反应的，必须拖动。这里在外层补了 `toggleable`：轻点任意位置切换，
 *    从旋钮上拖动仍然是滑动切换（拖动会消费事件，外层点击自动取消，两者不打架）。
 * 2. 外层做成 48dp 高的触摸区，视觉仍是 28dp 的轨道。28dp 达不到最小触摸目标，
 *    直接用它当点击区在真机上会漏点。
 *
 * @param selected 当前状态。组件自身不持有状态，只负责表现与手势。
 * @param backdrop **必须**是应用里那唯一一个共享 backdrop。
 */
@Composable
fun GlassToggle(
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val tokens = LocalGlassTokens.current
    val haptics = rememberGlassHaptics()
    val animationScope = rememberCoroutineScope()
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val density = LocalDensity.current

    // 轨道 64dp 宽、旋钮 40dp 宽、两侧各留 2dp，所以可滑动距离是 20dp。
    val dragWidth = with(density) { 20f.dp.toPx() }

    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (selected) 1f else 0f) }

    val dragWidthState = rememberUpdatedState(dragWidth)
    val onSelectState = rememberUpdatedState(onSelect)
    val selectedState = rememberUpdatedState(selected)

    val dragAnimation = remember(animationScope, isLtr) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStopped = {
                // didDrag 为假说明这次拖动几乎没有横向位移（比如竖着划了一下），
                // 那就按「点了一下」处理，直接翻转。
                val targetValue = if (didDrag) {
                    if (targetValue >= 0.5f) 1f else 0f
                } else {
                    if (selectedState.value) 0f else 1f
                }
                didDrag = false
                fraction = targetValue
                animateToValue(targetValue)
                haptics.toggle(targetValue == 1f)
                onSelectState.value(targetValue == 1f)
            },
            onDrag = { _, dragAmount ->
                if (!didDrag) {
                    didDrag = dragAmount.x != 0f
                }
                val delta = dragAmount.x / dragWidthState.value
                fraction = if (isLtr) {
                    (fraction + delta).coerceIn(0f, 1f)
                } else {
                    (fraction - delta).coerceIn(0f, 1f)
                }
            }
        )
    }

    // 拖动过程中把手指位置推给弹簧。
    LaunchedEffect(dragAnimation) {
        snapshotFlow { fraction }.collectLatest { dragAnimation.updateValue(it) }
    }

    // 外部状态变化时把旋钮滑过去。首次组合两边相等，不会白播一次按压动画。
    LaunchedEffect(selected, dragAnimation) {
        val target = if (selected) 1f else 0f
        if (fraction != target) {
            fraction = target
            dragAnimation.animateToValue(target)
        }
    }

    val knobEffect = tokens.effect(GlassRoles.SwitchKnob, chromaticAberration = true)
    val trackOffColor = tokens.contentColor.copy(alpha = 0.12f)
    val trackOnColor = Colour.IntensityOn

    Box(
        modifier
            .height(48.dp)
            .toggleable(value = selected, enabled = enabled, role = Role.Switch) { checked ->
                haptics.toggle(checked)
                onSelect(checked)
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .clip(Capsule())
                .drawBehind {
                    drawRect(lerp(trackOffColor, trackOnColor, dragAnimation.value))
                }
                .size(64.dp, 28.dp)
        )

        Box(
            Modifier
                .graphicsLayer {
                    val offset = 2f.dp.toPx() + dragWidthState.value * dragAnimation.value
                    translationX = if (isLtr) offset else -offset
                }
                .then(dragAnimation.modifier)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        // 静息：只有模糊。按下去：模糊退场，折射登场——官方蓝本的原始配比。
                        val progress = dragAnimation.pressProgress
                        applyGlassSurface(
                            tokens = tokens,
                            effect = knobEffect.copy(
                                blurRadius = knobEffect.blurRadius * (1f - progress),
                                refractionHeight = knobEffect.refractionHeight * progress,
                                refractionAmount = knobEffect.refractionAmount * progress
                            )
                        )
                    },
                    highlight = {
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = dragAnimation.pressProgress
                        )
                    },
                    shadow = { Shadow(radius = 6.dp, alpha = 0.5f) },
                    innerShadow = {
                        val progress = dragAnimation.pressProgress
                        InnerShadow(radius = 4.dp * progress, alpha = progress)
                    },
                    layerBlock = {
                        val velocity = dragAnimation.velocity / 50f
                        scaleX = squashScale(dragAnimation.scaleX, velocity, alongX = true)
                        scaleY = squashScale(dragAnimation.scaleY, velocity, alongX = false)
                    },
                    onDrawSurface = {
                        // 低端档位没有模糊，旋钮必须更实一点，否则和轨道糊在一起分不出边界。
                        val restAlpha = if (tokens.tier == GlassTier.Translucent) 0.95f else 0.88f
                        drawRect(
                            color = tokens.contentColor,
                            alpha = restAlpha - 0.55f * dragAnimation.pressProgress
                        )
                    }
                )
                .size(40.dp, 24.dp)
        )
    }
}

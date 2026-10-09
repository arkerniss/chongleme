package com.vertin.chongleme.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 玻璃滑块。
 *
 * 改写自官方 catalog 的 `components/LiquidSlider.kt`（Apache-2.0）。
 *
 * 效果参数按蓝本原样：静息时滑块 `blur(8.dp)`，按下去模糊退场、换成
 * `lens(10.dp, 14.dp, chromaticAberration = true)` + `Highlight.Ambient` + `Shadow` + `InnerShadow`。
 *
 * 相对蓝本改了两处，都是为了手感：
 *
 * 1. **整条轨道都能按、都能拖。** 蓝本把拖动检测只挂在 40×24dp 的滑块上、把点击定位挂在轨道上，
 *    结果是「想调一下得先精准按住那个小圆片」。这里用一个 `awaitEachGesture` 循环同时覆盖
 *    按下即定位 + 持续拖动，触摸区是整条 48dp 高的轨道。
 * 2. 因此**没有**把 `DampedDragAnimation.modifier` 挂到滑块上：父节点的按下会先消费事件，
 *    子节点的 `detectDragGestures` 再也不会越过 touch slop，挂了也是死代码。
 *    按压反馈改为在同一个手势循环里手动调 `press()` / `release()`，行为一致且没有冲突。
 *
 * @param value 当前值。组件不持有值，是受控的。
 * @param backdrop **必须**是应用里那唯一一个共享 backdrop。
 * @param steps 大于 0 时吸附到离散档位（`steps = 4` 表示 5 个位置），跨档时给一次触感。
 */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    require(valueRange.endInclusive > valueRange.start) {
        "GlassSlider 的 valueRange 必须是递增区间，收到 $valueRange。"
    }
    require(steps >= 0) { "GlassSlider 的 steps 不能为负，收到 $steps。" }

    val tokens = LocalGlassTokens.current
    val haptics = rememberGlassHaptics()
    val animationScope = rememberCoroutineScope()
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr

    val initialFraction = fractionOf(value, valueRange)
    var fraction by remember { mutableFloatStateOf(initialFraction) }
    var lastStep by remember { mutableIntStateOf(stepIndex(initialFraction, steps)) }

    val onValueChangeState = rememberUpdatedState(onValueChange)
    val onValueChangeFinishedState = rememberUpdatedState(onValueChangeFinished)
    val rangeState = rememberUpdatedState(valueRange)
    val stepsState = rememberUpdatedState(steps)

    val dragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = initialFraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStopped = { onValueChangeFinishedState.value?.invoke() }
        )
    }

    // 手势位置 → 状态 → 外部回调。三个状态每次都写最新的，避免旋转/换 range 后用旧闭包。
    val gestureModifier = Modifier.pointerInput(enabled, isLtr, valueRange, steps) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            dragAnimation.press()

            fun emitAt(x: Float) {
                val width = size.width
                if (width <= 0) return
                val target = fractionAt(x, width, isLtr, stepsState.value)
                val stepped = stepIndex(target, stepsState.value)
                if (stepped != lastStep) {
                    lastStep = stepped
                    haptics.tick()
                }
                fraction = target
                onValueChangeState.value(valueAt(target, rangeState.value))
            }

            // 按下即定位，不用先拖起来。
            //
            // 这里刻意**不消费**按下事件：滑块大概率躺在可滚动容器里（调参页就是），
            // 一旦消费掉，从滑块上起手的竖向滚动就再也滚不动了。不消费的话，
            // 外层滚动越过它的 slop 后会消费移动事件，下面的循环检测到就会退出。
            emitAt(down.position.x)

            var dragging = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                // 外层（滚动容器）接管了这次手势，让路。
                if (change.isConsumed) break
                if (change.positionChanged()) {
                    if (!dragging &&
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    ) {
                        dragging = true
                    }
                    if (dragging) {
                        change.consume()
                        emitAt(change.position.x)
                    }
                }
            }

            dragAnimation.release()
            onValueChangeFinishedState.value?.invoke()
        }
    }

    // 手指位置推给弹簧，滑块才不是硬跟手。
    LaunchedEffect(dragAnimation) {
        snapshotFlow { fraction }.collectLatest { dragAnimation.updateValue(it) }
    }

    // 外部改值 → 滑块滑过去。拖动过程中两者已经一致，这里不会打架。
    LaunchedEffect(value, dragAnimation) {
        val target = fractionOf(value, rangeState.value)
        if (abs(dragAnimation.targetValue - target) > 0.0001f) {
            fraction = target
            dragAnimation.animateToValue(target)
        }
    }

    val thumbEffect = tokens.effect(GlassRoles.SliderThumb, chromaticAberration = true)
    val trackColor = tokens.contentColor.copy(alpha = 0.12f)
    val fillColor = Colour.IntensityOn

    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .then(gestureModifier)
            .semantics {
                if (!enabled) disabled()
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
                setProgress { target ->
                    if (!enabled) {
                        false
                    } else {
                        onValueChangeState.value(target.coerceIn(valueRange))
                        true
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // 轨道
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(Capsule())
                .background(trackColor)
        )

        // 已填充部分：宽度在 layout 阶段算，值变化只失效布局，不重组。
        Box(
            Modifier
                .height(6.dp)
                .clip(Capsule())
                .background(fillColor)
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val available =
                        if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
                    val width = (available * dragAnimation.progress).roundToInt()
                    layout(width, placeable.height) { placeable.place(0, 0) }
                }
        )

        // 滑块
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val available =
                        if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
                    val travel = available - placeable.width
                    val x = (travel * dragAnimation.progress).roundToInt()
                    layout(available, placeable.height) { placeable.place(x, 0) }
                }
                .size(40.dp, 24.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        // 静息：只有模糊。按下去：模糊退场，折射登场——官方蓝本的原始配比。
                        val progress = dragAnimation.pressProgress
                        applyGlassSurface(
                            tokens = tokens,
                            effect = thumbEffect.copy(
                                blurRadius = thumbEffect.blurRadius * (1f - progress),
                                refractionHeight = thumbEffect.refractionHeight * progress,
                                refractionAmount = thumbEffect.refractionAmount * progress
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
                        val velocity = dragAnimation.velocity / 10f
                        scaleX = squashScale(dragAnimation.scaleX, velocity, alongX = true)
                        scaleY = squashScale(dragAnimation.scaleY, velocity, alongX = false)
                    },
                    onDrawSurface = {
                        // 低端档位没有模糊，滑块必须更实一点，否则和轨道糊在一起。
                        val restAlpha = if (tokens.tier == GlassTier.Translucent) 0.95f else 0.88f
                        drawRect(
                            color = tokens.contentColor,
                            alpha = restAlpha - 0.55f * dragAnimation.pressProgress
                        )
                    }
                )
        )
    }
}

/** 值 → 0..1 的比例。 */
private fun fractionOf(value: Float, range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    return ((value - range.start) / span).coerceIn(0f, 1f)
}

/** 0..1 的比例 → 值。 */
private fun valueAt(fraction: Float, range: ClosedFloatingPointRange<Float>): Float =
    range.start + fraction * (range.endInclusive - range.start)

/** 屏幕横坐标 → 0..1 的比例，并按 [steps] 吸附。 */
private fun fractionAt(x: Float, widthPx: Int, isLtr: Boolean, steps: Int): Float {
    val raw = (x / widthPx).coerceIn(0f, 1f)
    val directed = if (isLtr) raw else 1f - raw
    return if (steps <= 0) directed else (directed * steps).roundToInt().toFloat() / steps
}

/** 当前落在第几个档位上（[steps] 为 0 时恒为 0）。 */
private fun stepIndex(fraction: Float, steps: Int): Int =
    if (steps <= 0) 0 else (fraction * steps).roundToInt()

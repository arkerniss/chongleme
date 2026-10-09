package com.vertin.chongleme.ui.glass

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 带阻尼的拖动动画：把「手指位置」变成「一串带弹簧的值」。
 *
 * 标签栏胶囊的滑动、开关旋钮的滑动、滑块的手感都长在它上面。三件事被拆得很清楚：
 *
 * - [value]：经过弹簧平滑后的目标值。控件的位置应该读它，而不是读手指。
 * - [pressProgress]：0→1 的按下进度。玻璃的折射、阴影、内阴影都挂在它上面，
 *   所以「按下去玻璃才浮起来」这件事不需要各控件自己写一套动画。
 * - [velocity]：拖动速度，用来做挤压/拉伸（快速甩动时胶囊会短暂变形）。
 *
 * 改写自官方 catalog 的 `utils/DampedDragAnimation.kt`（Apache-2.0）。相对蓝本改了三处：
 *
 * 1. 手势检测换成 foundation 的 `detectDragGestures`。蓝本用的是 catalog 自己的
 *    `inspectDragGestures`（带一个调试用的手势检查器），那是 demo 专属工具，不属于库 API。
 * 2. 去掉了 `release()` 里等 `value` 追上 `targetValue` 的那段逻辑——它依赖 catalog 的
 *    `awaitFrame()`，而且只在标签栏拖拽收尾时才有意义，收益远小于复杂度。
 * 3. 速度采样用 `SystemClock.uptimeMillis()`，避免引入 `kotlin.time.Clock`
 *    （在 Kotlin 2.4 上仍属实验 API，需要额外 opt-in）。
 */
class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    initialValue: Float,
    private val valueRange: ClosedRange<Float>,
    visibilityThreshold: Float,
    private val initialScale: Float,
    private val pressedScale: Float,
    private val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit = {},
    private val onDragStopped: DampedDragAnimation.() -> Unit = {},
    private val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit = { _, _ -> }
) {

    private val valueAnimationSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec = spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()

    /** 弹簧平滑后的当前位置。 */
    val value: Float get() = valueAnimation.value

    /** 在 [valueRange] 中的进度，0..1。 */
    val progress: Float get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)

    /** 动画的目标值。拖动时应基于它累加，而不是基于 [value]，否则会追着弹簧跑。 */
    val targetValue: Float get() = valueAnimation.targetValue

    /** 按下进度 0..1。 */
    val pressProgress: Float get() = pressProgressAnimation.value

    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    /**
     * 拖动修饰符。挂在被拖动的那个节点上。
     *
     * 注意它只处理「拖」，不处理「点」——`detectDragGestures` 要越过 touch slop 才会回调。
     * 需要点按的控件（开关、滑块）请在这个节点之外再挂 `toggleable` / 自己的手势循环，
     * 点按时子节点的拖动检测不消费事件，父节点的点击就能正常触发。
     */
    val modifier: Modifier = Modifier.pointerInput(this) {
        detectDragGestures(
            onDragStart = { down ->
                onDragStarted(down)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            }
        ) { change, dragAmount ->
            change.consume()
            onDrag(size, dragAmount)
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    /** 不等弹簧，直接朝目标值追（拖动过程中用这个）。 */
    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch {
            launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() } }
        }
    }

    /** 带按下反馈的跳转（点按轨道、切换标签时用这个）。 */
    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val targetValue = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(SystemClock.uptimeMillis(), Offset(value, 0f))
        val targetVelocity =
            velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}

/**
 * 按拖动速度给缩放做挤压：横向快速甩动时拉长、纵向压扁，反之亦然。
 *
 * 幅度按官方蓝本夹在 ±0.2，避免甩得猛时形变到失真。
 * 直接写成一个函数而不是在三个组件里各抄一遍，是因为这三个组件的挤压公式必须一致，
 * 否则标签栏和滑块的手感会对不上。
 */
internal fun squashScale(scale: Float, velocity: Float, alongX: Boolean): Float {
    val damped = (velocity * if (alongX) 0.75f else 0.25f).coerceIn(-0.2f, 0.2f)
    return if (alongX) scale / (1f - damped) else scale * (1f - damped)
}

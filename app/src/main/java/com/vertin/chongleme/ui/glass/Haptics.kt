package com.vertin.chongleme.ui.glass

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * 触感反馈的收口处。
 *
 * 玻璃控件的手感很大一部分来自触感：按下去的那一下、开关拨到位的那一下、滑块经过刻度的那一下。
 * 把这些调用散在各个组件里，就会出现「同一个动作在按钮上是 LongPress、在标签上是 ContextClick」
 * 这种不一致；收成一个类之后，语义（tap / press / toggle / tick）是固定的，具体映射到哪种
 * 系统触感只在这里决定。
 *
 * [enabled] 是给调参页与测试留的总开关。
 */
@Stable
class GlassHaptics internal constructor(
    private val hapticFeedback: HapticFeedback,
    private val enabled: Boolean
) {

    /** 轻点：标签切换、按钮点击。 */
    fun tap() {
        perform(HapticFeedbackType.ContextClick)
    }

    /** 按住：拖动开始。 */
    fun press() {
        perform(HapticFeedbackType.LongPress)
    }

    /**
     * 开关到位。
     *
     * `ToggleOn` / `ToggleOff` 是 Android 14 才有的系统触感。更早的系统拿到未知常量不会崩，
     * 但也不会有任何反馈——所以老设备退回到 `ContextClick`，至少保证「拨到位了」这件事有回音。
     */
    fun toggle(isOn: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            perform(if (isOn) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        } else {
            perform(HapticFeedbackType.ContextClick)
        }
    }

    /** 刻度感：滑块跨过整数档位。 */
    fun tick() {
        perform(HapticFeedbackType.SegmentTick)
    }

    private fun perform(type: HapticFeedbackType) {
        if (enabled) hapticFeedback.performHapticFeedback(type)
    }
}

/**
 * 取当前上下文的触感入口。
 *
 * 放在 Composable 里而不是让组件直接 `LocalHapticFeedback.current`，是为了 [enabled]
 * 这一个开关能一次性关掉全应用触感。
 */
@Composable
fun rememberGlassHaptics(enabled: Boolean = true): GlassHaptics {
    val hapticFeedback = LocalHapticFeedback.current
    return remember(hapticFeedback, enabled) { GlassHaptics(hapticFeedback, enabled) }
}

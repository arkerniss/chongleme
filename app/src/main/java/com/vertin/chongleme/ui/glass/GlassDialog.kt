package com.vertin.chongleme.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedRectangle
import com.vertin.chongleme.theme.Colour
import com.vertin.chongleme.theme.LocalGlassTokens

/**
 * 玻璃对话框。
 *
 * ## 为什么不用 `androidx.compose.ui.window.Dialog`
 *
 * 因为它会把内容放进**一个新的 Window**。而 backdrop 库的 `LayerBackdrop.drawBackdrop`
 * 依赖同一棵 `LayoutCoordinates` 树做坐标换算（内部是 `localPositionOf`）：跨 Window 之后
 * 拿不到壁纸图层的坐标，采样直接失效，玻璃会退化成一块空白。
 *
 * 这个限制不是可以绕过的细节——它决定了整个应用的浮层都必须是**树内浮层**。
 * 所以这里自己铺一层遮罩 + 居中一块玻璃面板，进/出场用 `AnimatedVisibility` 做淡入缩放。
 *
 * 用法上有一条硬约束：**必须放在整屏 `Box` 的最后一层**，否则遮罩盖不满屏幕。
 *
 * ```kotlin
 * Box(Modifier.fillMaxSize()) {
 *     Wallpaper()
 *     Content()
 *     GlassDialog(visible = showDialog, onDismissRequest = { showDialog = false }, backdrop = backdrop) { ... }
 * }
 * ```
 *
 * @param dismissOnBackPress 返回键是否关闭。命名对齐 Material 的 `DialogProperties`，
 *   调用方不需要记两套名字。
 * @param dismissOnClickOutside 点遮罩是否关闭。需要「必须做出选择」的确认框时传 `false`。
 */
@Composable
fun GlassDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 22.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = LocalGlassTokens.current

    BackHandler(enabled = visible && dismissOnBackPress, onBack = onDismissRequest)

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + scaleIn(initialScale = 0.94f),
        exit = fadeOut() + scaleOut(targetScale = 0.96f)
    ) {
        Box(Modifier.fillMaxSize()) {

            Box(
                Modifier
                    .fillMaxSize()
                    // 遮罩对无障碍是纯装饰，不该被读出来。
                    .clearAndSetSemantics {}
                    .drawBehind {
                        // 刻意用背景色的深色调而不是纯黑：纯黑会把壁纸压死，
                        // 玻璃面板也就失去了「浮在同一个空间里」的感觉。
                        drawRect(Colour.BackgroundTop.copy(alpha = 0.62f))
                    }
                    .then(
                        if (dismissOnClickOutside) {
                            Modifier.pointerInput(onDismissRequest) {
                                detectTapGestures { onDismissRequest() }
                            }
                        } else {
                            Modifier.pointerInput(Unit) { detectTapGestures { } }
                        }
                    )
            )

            GlassPanel(
                backdrop = backdrop,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp)
                    // 先卡最大宽度再 fillMaxWidth，宽屏上对话框才不会被拉成一整条。
                    .widthIn(max = 420.dp)
                    .fillMaxWidth(),
                shape = RoundedRectangle(tokens.cornerRadius),
                shadow = Shadow(
                    radius = 28.dp,
                    color = Color.Black.copy(alpha = 0.22f)
                )
            ) {
                Column(
                    Modifier.padding(contentPadding),
                    content = content
                )
            }
        }
    }
}

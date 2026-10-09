package com.vertin.chongleme.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.vertin.chongleme.theme.GlassRoles
import com.vertin.chongleme.theme.GlassTier
import com.vertin.chongleme.theme.LocalGlassContentColor
import com.vertin.chongleme.theme.LocalGlassTokens
import com.vertin.chongleme.theme.applyGlassSurface
import com.vertin.chongleme.theme.effect
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * 底部玻璃标签栏：一条胶囊形的玻璃条 + 一个会跟着手滑动的选中胶囊。
 *
 * 改写自官方 catalog 的 `components/LiquidBottomTabs.kt`（Apache-2.0）。
 *
 * 效果参数按蓝本原样：玻璃条 `vibrancy() + blur(8.dp) + lens(24.dp, 24.dp)`；
 * 选中胶囊 `lens(10.dp, 14.dp, chromaticAberration = true)` + `Shadow` + `InnerShadow`。
 *
 * ## 与蓝本的关键差异（不是偷懒，是被「全应用只有一个 backdrop」这条纪律逼出来的）
 *
 * 蓝本为了做出「选中胶囊能折射出下方被染色的图标」，在组件内部又开了一层 layer backdrop
 * 把图标录进去，再把它和壁纸 backdrop 合并采样。那意味着**多一次全屏离屏渲染**，
 * 而且这个组件每出现在一个屏幕就要多付一次。
 *
 * 本项目禁止组件自建 backdrop，所以这里换了个画法，把三个兄弟节点按 z 序叠起来：
 *
 * ```
 *   ┌─ 图标行（最上层，永远可见，选中项用满色、未选中用弱色）
 *   ├─ 选中胶囊（中层，就是那块玻璃，折射的是壁纸）
 *   └─ 玻璃条本体（最下层）
 * ```
 *
 * 蓝本把胶囊画在最上层，靠「胶囊去采样录有图标的那个 layer」来让图标透过玻璃显示；
 * 我们拿不到那层 layer，于是把顺序倒过来——图标本来就画在胶囊之上，不需要采样。
 * 视觉结果几乎一致（都是一块有透镜边缘的玻璃托着选中的图标），但少一层离屏渲染。
 *
 * @param selectedIndex 当前选中项，从 0 开始。
 * @param onTabSelected 点按或拖动结束后回调，参数是落定的下标。
 * @param backdrop **必须**是应用里那唯一一个共享 backdrop。
 * @param tabCount **必须**与 `content` 里 [GlassTab] 的数量一致——标签栏要靠它算每格的宽度。
 *   槽位式 API 拿不到子节点数量，这个约束只能靠调用方遵守，所以在这里 `require` 了一道。
 */
@Composable
fun GlassTabBar(
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    backdrop: Backdrop,
    tabCount: Int,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    require(tabCount > 0) { "GlassTabBar 的 tabCount 必须大于 0，收到 $tabCount。" }

    val tokens = LocalGlassTokens.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val density = LocalDensity.current
    val animationScope = rememberCoroutineScope()

    val barEffect = tokens.effect(GlassRoles.TabBar)
    val capsuleEffect = tokens.effect(GlassRoles.TabCapsule, chromaticAberration = true)

    val highlight = remember(animationScope, tokens.tier) {
        InteractiveHighlight(animationScope = animationScope, tier = tokens.tier)
    }
    val onTabSelectedState = rememberUpdatedState(onTabSelected)

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        // 玻璃条本身 64dp 高，内容区四周各留 4dp，所以每格宽度 = (总宽 - 8dp) / 格数。
        val tabWidth = with(density) { (constraints.maxWidth - 8f.dp.toPx()) / tabCount }
        val tabWidthState = rememberUpdatedState(tabWidth)
        val capsuleWidth = with(density) { tabWidth.toDp() }

        // 拖动时整条玻璃会朝拖动方向让开最多 4dp——这是「液体」感里最便宜也最有效的一笔。
        val panelOffsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density, constraints.maxWidth) {
            derivedStateOf {
                val fraction = (panelOffsetAnimation.value / constraints.maxWidth).coerceIn(-1f, 1f)
                with(density) { 4f.dp.toPx() * sign(fraction) * EaseOut.transform(abs(fraction)) }
            }
        }

        val dragAnimation = remember(animationScope, tabCount, isLtr) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..(tabCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStopped = {
                    val target = targetValue.roundToInt().coerceIn(0, tabCount - 1)
                    animateToValue(target.toFloat())
                    animationScope.launch {
                        panelOffsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                    onTabSelectedState.value(target)
                },
                onDrag = { _, dragAmount ->
                    // tabWidth 从 rememberUpdatedState 里取，避免旋转屏幕后
                    // remember 住的闭包还在用旧的格子宽度。
                    val width = tabWidthState.value
                    updateValue(
                        (targetValue + dragAmount.x / width * if (isLtr) 1f else -1f)
                            .coerceIn(0f, (tabCount - 1).toFloat())
                    )
                    animationScope.launch {
                        panelOffsetAnimation.snapTo(panelOffsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }

        // 外部改变选中项 → 胶囊滑过去。首次组合时两者已经相等，不会白播一次按压动画。
        LaunchedEffect(selectedIndex, dragAnimation) {
            val target = selectedIndex.coerceIn(0, tabCount - 1).toFloat()
            if (dragAnimation.targetValue != target) {
                dragAnimation.animateToValue(target)
            }
        }

        val tabScale = remember(dragAnimation) {
            { lerp(1f, 1.12f, dragAnimation.pressProgress) }
        }

        // ── 最下层：玻璃条本体 ────────────────────────────────────────────
        Box(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = { applyGlassSurface(tokens, barEffect) },
                    layerBlock = {
                        if (size.width > 0f) {
                            val progress = dragAnimation.pressProgress
                            val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                            scaleX = scale
                            scaleY = scale
                        }
                    },
                    onDrawSurface = { drawRect(tokens.surface) }
                )
                .then(highlight.modifier)
                .then(highlight.gestureModifier)
                .height(64.dp)
                .fillMaxWidth()
        )

        // ── 中层：选中胶囊 ───────────────────────────────────────────────
        Box(
            Modifier
                .graphicsLayer {
                    // translationX 走原始像素，不会跟着 RTL 自动镜像，所以 RTL 下手动取反。
                    val direction = if (isLtr) 1f else -1f
                    translationX = direction * (4f.dp.toPx() + dragAnimation.value * tabWidth) +
                            panelOffset
                }
                .then(dragAnimation.modifier)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = { applyGlassSurface(tokens, capsuleEffect) },
                    highlight = {
                        Highlight.Default.copy(alpha = lerp(0.35f, 1f, dragAnimation.pressProgress))
                    },
                    shadow = {
                        Shadow(radius = 12.dp, alpha = lerp(0.35f, 1f, dragAnimation.pressProgress))
                    },
                    innerShadow = {
                        val progress = dragAnimation.pressProgress
                        InnerShadow(
                            radius = 8.dp * (0.45f + 0.55f * progress),
                            alpha = lerp(0.35f, 1f, progress)
                        )
                    },
                    layerBlock = {
                        val velocity = dragAnimation.velocity / 10f
                        scaleX = squashScale(dragAnimation.scaleX, velocity, alongX = true)
                        scaleY = squashScale(dragAnimation.scaleY, velocity, alongX = false)
                    },
                    onDrawSurface = {
                        val progress = dragAnimation.pressProgress
                        // 低端档位没有折射也没有模糊，选中项只能靠更实的底色才看得出来。
                        val restAlpha = if (tokens.tier == GlassTier.Translucent) 0.16f else 0.10f
                        drawRect(tokens.contentColor.copy(alpha = restAlpha), alpha = 1f - progress)
                        drawRect(tokens.surface, alpha = 0.7f * progress)
                    }
                )
                .width(capsuleWidth)
                .height(56.dp)
        )

        // ── 最上层：图标行 ───────────────────────────────────────────────
        CompositionLocalProvider(
            LocalGlassContentColor provides tokens.contentColor,
            LocalGlassTabScale provides tabScale
        ) {
            Row(
                Modifier
                    .graphicsLayer { translationX = panelOffset }
                    .height(64.dp)
                    .fillMaxWidth()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }
    }
}

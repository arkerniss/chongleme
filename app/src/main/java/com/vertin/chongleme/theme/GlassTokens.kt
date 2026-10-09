package com.vertin.chongleme.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.isRuntimeShaderSupported
import java.util.Locale

/**
 * 液体玻璃的渲染档位。
 *
 * 这三个档位对应 Android 平台真实存在的三条能力线，不是人为分的档：
 *
 * - [Translucent]：≤ Android 11。系统没有 `RenderEffect`，`graphicsLayer.renderEffect`
 *   写不进去，也就没有任何模糊。此时玻璃只剩「表面叠加色」——所以这一档必须把表面色
 *   压实（见 [GlassTokens.fallbackSurfaceColor]），让文字对比度自己站得住。
 * - [BlurVibrancy]：Android 12 / 12L。有 `RenderEffect`（模糊 + 增艳），但 AGSL runtime
 *   shader 要 13 才有，所以折射和色散一定是关闭的。`lens()` 内部会直接 return。
 * - [Refraction]：Android 13+。模糊 + 增艳 + 折射（含色散）全开。
 *
 * 档位由 [resolveGlassTier] 在 token 构造时算一次，不随每帧变化。
 */
enum class GlassTier {
    Translucent,
    BlurVibrancy,
    Refraction
}

/**
 * 判定当前设备实际能跑到哪一档。
 *
 * [forceLowEnd] 是为调参页与截图回归准备的开关：打开后无论真机多新都按最低档渲染，
 * 这样「低端机上会不会塌」这件事不需要真的找一台 Android 11 才能验证。
 */
internal fun resolveGlassTier(forceLowEnd: Boolean): GlassTier = when {
    forceLowEnd -> GlassTier.Translucent
    !isRenderEffectSupported() -> GlassTier.Translucent
    !isRuntimeShaderSupported() -> GlassTier.BlurVibrancy
    else -> GlassTier.Refraction
}

/**
 * 全库共用的玻璃效果参数。
 *
 * 这些值是**基准值**，对应「面板 / 卡片」这一量级；按钮、标签栏、开关旋钮这些更小的控件
 * 不会各自硬编码一套数字，而是按 [GlassRoles] 里的比例从基准折算（见 [effect]）。
 * 这么做的直接好处是：调参页动一个滑块，全应用所有玻璃控件同比变化；而默认值下每个控件
 * 又恰好落在官方 catalog 蓝本的原始数值上。
 *
 * 调参页实时改的就是这里前六项 + [forceLowEnd]。
 *
 * @param refractionHeight 折射高度：玻璃边缘「翘起来」的高度，越大边缘的透镜感越厚。
 * @param refractionAmount 折射量：采样点被推开的距离，越大背后的内容被拉得越弯。
 * @param blurRadius 模糊半径。
 * @param chromaticAberration 色散开关：开启后边缘会出现极轻微的彩虹分离。
 * @param cornerRadius 容器圆角（按钮与标签栏用 Capsule，不受这一项影响）。
 * @param surfaceColor 正常档位下叠在玻璃上的表面色。
 * @param fallbackSurfaceColor 低端档位（[GlassTier.Translucent]）用的表面色：没有模糊时
 *   必须靠它把文字托起来。
 * @param contentColor 玻璃上的主文字色。
 * @param contentMutedColor 玻璃上的次要文字色。
 * @param forceLowEnd 强制按最低档渲染。
 */
@Immutable
data class GlassTokens(
    val refractionHeight: Dp = 12.dp,
    val refractionAmount: Dp = 18.dp,
    val blurRadius: Dp = 14.dp,
    val chromaticAberration: Boolean = false,
    val cornerRadius: Dp = 24.dp,
    val surfaceColor: Color = Colour.SurfaceDark.copy(alpha = 0.34f),
    val fallbackSurfaceColor: Color = Colour.SurfaceDark.copy(alpha = 0.88f),
    val contentColor: Color = Colour.InkOnDark,
    val contentMutedColor: Color = Colour.InkMutedOnDark,
    val forceLowEnd: Boolean = false
) {

    /** 本机（或 [forceLowEnd]）实际能跑到的渲染档位。 */
    val tier: GlassTier = resolveGlassTier(forceLowEnd)

    /**
     * 真正要画到玻璃上的表面色。
     *
     * 正常档位用 [surfaceColor]（很淡，让折射和模糊当主角）；低端档位换成
     * [fallbackSurfaceColor]（近乎不透明，保证文字对比度）。
     */
    val surface: Color
        get() = if (tier == GlassTier.Translucent) fallbackSurfaceColor else surfaceColor

    /**
     * 导出成可以直接粘回代码里的 Kotlin 片段。调参页顶部的「复制参数」用的就是它。
     * 刻意输出 `dp` / `Color(0x..)` 的字面量形式，粘进源码就能编译。
     */
    fun toKotlinSource(): String = buildString {
        appendLine("// 由调参页导出，tier = $tier")
        appendLine("GlassTokens(")
        appendLine("    refractionHeight = ${refractionHeight.value}.dp,")
        appendLine("    refractionAmount = ${refractionAmount.value}.dp,")
        appendLine("    blurRadius = ${blurRadius.value}.dp,")
        appendLine("    chromaticAberration = $chromaticAberration,")
        appendLine("    cornerRadius = ${cornerRadius.value}.dp,")
        appendLine("    surfaceColor = Color(${surfaceColor.toHexLiteral()}),")
        appendLine("    fallbackSurfaceColor = Color(${fallbackSurfaceColor.toHexLiteral()}),")
        appendLine("    forceLowEnd = $forceLowEnd")
        append(')')
    }

    companion object {

        /**
         * 唯一的玻璃 token 档。**本项目刻意只有深色**。
         *
         * 这里原本还有一个 `Light` 档，但那是半成品：全应用约 80 处文字直接硬编码了
         * `Colour.InkOnDark`（近白），根本不读 token 里的 `contentColor`，
         * 于是系统切到浅色时会得到「浅底 + 近白字」，对比度约 1.04:1，界面等于不可读。
         *
         * 与其留一个切过去就坏的分支，不如不放这个开关：这是个夜里用的私人本子，
         * 浅色版本需要重新设计整套玻璃的对比度与表面色，不是换几个色值的事。
         * 真要做浅色，正确做法是引入一个 `LocalInk` 组合局部、把那些硬编码色值全部
         * 收敛到它，再逐屏核对对比度——那是独立的一项工作。
         */
        val Dark = GlassTokens()
    }
}

private fun Color.toHexLiteral(): String =
    String.format(Locale.ROOT, "0x%08X", toArgb())

/**
 * 某个控件角色相对基准 token 的比例系数。
 *
 * 只做等比缩放，不改变各效果之间的相对关系。
 */
@Immutable
data class GlassEffectFactors(
    val blur: Float = 1f,
    val refractionHeight: Float = 1f,
    val refractionAmount: Float = 1f
)

/**
 * 各控件角色相对基准值的比例。
 *
 * 分母（12dp / 18dp / 14dp）就是 [GlassTokens] 的默认基准值，分子是官方 catalog 蓝本里
 * 那个控件的原始数值。所以默认 token 下，每个控件算出来的值都精确等于官方数值：
 *
 * | 控件 | 官方 blur | 官方 lens(高度, 量) | 官方色散 |
 * |---|---|---|---|
 * | LiquidButton（→ [GlassRoles.Button]） | 2dp | 12dp, 24dp | 否 |
 * | LiquidBottomTabs 玻璃条（→ [GlassRoles.TabBar]） | 8dp | 24dp, 24dp | 否 |
 * | LiquidBottomTabs 选中胶囊（→ [GlassRoles.TabCapsule]） | — | 10dp, 14dp | 是 |
 * | LiquidToggle 旋钮（→ [GlassRoles.SwitchKnob]） | 8dp | 5dp, 10dp | 是 |
 * | LiquidSlider 滑块（→ [GlassRoles.SliderThumb]） | 8dp | 10dp, 14dp | 是 |
 *
 * 把这张表集中放在一处，是为了避免「数值散落在八个组件文件里、调参页动了却只有一半控件响应」。
 */
object GlassRoles {

    /** 面板 / 卡片：直接用基准值（blur 14dp + 轻折射 + 圆角 24dp）。 */
    val Panel = GlassEffectFactors()

    /** LiquidButton。 */
    val Button = GlassEffectFactors(
        blur = 2f / 14f,
        refractionHeight = 12f / 12f,
        refractionAmount = 24f / 18f
    )

    /** LiquidBottomTabs 的玻璃条本体。 */
    val TabBar = GlassEffectFactors(
        blur = 8f / 14f,
        refractionHeight = 24f / 12f,
        refractionAmount = 24f / 18f
    )

    /** LiquidBottomTabs 的选中胶囊：官方这个部件不用模糊，只做折射。 */
    val TabCapsule = GlassEffectFactors(
        blur = 0f,
        refractionHeight = 10f / 12f,
        refractionAmount = 14f / 18f
    )

    /** LiquidToggle 的旋钮。 */
    val SwitchKnob = GlassEffectFactors(
        blur = 8f / 14f,
        refractionHeight = 5f / 12f,
        refractionAmount = 10f / 18f
    )

    /** LiquidSlider 的滑块。 */
    val SliderThumb = GlassEffectFactors(
        blur = 8f / 14f,
        refractionHeight = 10f / 12f,
        refractionAmount = 14f / 18f
    )
}

/**
 * 由基准 token 折算出的、某个控件实际要用的效果参数。
 */
@Immutable
data class GlassEffect(
    val blurRadius: Dp,
    val refractionHeight: Dp,
    val refractionAmount: Dp,
    val chromaticAberration: Boolean
)

/**
 * 按角色比例从基准值算出这个控件自己的效果参数。
 *
 * @param chromaticAberration 默认跟随 token 的总开关；标签栏选中胶囊、开关旋钮、滑块这三个
 *   官方蓝本里显式开了色散的部件会单独传 `true`。
 */
fun GlassTokens.effect(
    factors: GlassEffectFactors = GlassEffectFactors(),
    chromaticAberration: Boolean = this.chromaticAberration
): GlassEffect = GlassEffect(
    blurRadius = blurRadius * factors.blur,
    refractionHeight = refractionHeight * factors.refractionHeight,
    refractionAmount = refractionAmount * factors.refractionAmount,
    chromaticAberration = chromaticAberration
)

/**
 * 把效果参数写进 backdrop 的 effect scope。**全库唯一一处降级分支**。
 *
 * 为什么要在调用之前自己判档，而不是直接无脑调 `blur()` / `lens()`：
 * 库里那几个函数内部确实也各有一道 `isRenderEffectSupported()` 守卫，靠它们也能不崩；
 * 但那样一来「低端机上到底跑了哪些效果」就散落在库的实现细节里，读代码的人必须去翻库源码
 * 才知道会不会塌。这里显式分档，行为一目了然，也顺手让低端档免掉一次 AGSL 着色器构造。
 *
 * 调用方要把 [GlassTokens.surface] 画在 `onDrawSurface` 里——低端档全靠它撑对比度。
 */
fun BackdropEffectScope.applyGlassSurface(
    tokens: GlassTokens,
    effect: GlassEffect
) {
    when (tokens.tier) {
        GlassTier.Translucent -> Unit

        GlassTier.BlurVibrancy -> {
            vibrancy()
            blur(effect.blurRadius.toPx())
        }

        GlassTier.Refraction -> {
            vibrancy()
            blur(effect.blurRadius.toPx())
            lens(
                refractionHeight = effect.refractionHeight.toPx(),
                refractionAmount = effect.refractionAmount.toPx(),
                chromaticAberration = effect.chromaticAberration
            )
        }
    }
}

/**
 * 当前生效的玻璃参数。
 *
 * 用 `compositionLocalOf` 而不是 `static...`：调参页每拖一下滑块都会换一个新 token，
 * 静态局部会把这个 provider 以下的整棵子树全部重组；动态版本只让真正读了 token 的
 * 玻璃组件重组。
 *
 * 组件一律通过它取参数，不各自开参数——这样加一个新控件不需要改任何调用方。
 */
val LocalGlassTokens: ProvidableCompositionLocal<GlassTokens> = compositionLocalOf { GlassTokens.Dark }

/**
 * 提供一套玻璃参数给子树。调参页就是靠它把实时改动的参数推给全应用的玻璃控件。
 */
@Composable
fun ProvideGlassTokens(tokens: GlassTokens, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassTokens provides tokens, content = content)
}

/**
 * 玻璃上的内容色。所有玻璃组件都会在内容插槽外面提供它，
 * 所以插槽里放 icon / 文字时直接读 `LocalGlassContentColor.current` 即可，
 * 不需要调用方手动传色。
 */
val LocalGlassContentColor: ProvidableCompositionLocal<Color> = staticCompositionLocalOf { Color.Unspecified }

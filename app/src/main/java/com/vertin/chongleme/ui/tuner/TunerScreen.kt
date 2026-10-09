package com.vertin.chongleme.ui.tuner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.RoundedRectangle
import com.vertin.chongleme.BuildConfig
import com.vertin.chongleme.theme.Colour
import com.vertin.chongleme.theme.GlassTokens
import com.vertin.chongleme.theme.GlassTier
import com.vertin.chongleme.theme.LocalGlassContentColor
import com.vertin.chongleme.theme.ProvideGlassTokens
import com.vertin.chongleme.ui.glass.GlassButton
import com.vertin.chongleme.ui.glass.GlassCard
import com.vertin.chongleme.ui.glass.GlassDialog
import com.vertin.chongleme.ui.glass.GlassPanel
import com.vertin.chongleme.ui.glass.GlassSlider
import com.vertin.chongleme.ui.glass.GlassTab
import com.vertin.chongleme.ui.glass.GlassTabBar
import com.vertin.chongleme.ui.glass.GlassToggle
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * 玻璃调参页 —— **只在 debug 构建里存在**。
 *
 * 六个实时滑块（折射高度 / 折射量 / 模糊半径 / 色散 / 圆角 / 表面色）+ 两个开关
 * （色散、强制低端档），外加一块示例区，改一下就能在同一屏里看到所有控件跟着变。
 * 顶部「复制参数」把当前值导出成可以直接粘回 `GlassTokens` 的 Kotlin 片段。
 *
 * ## release 包里它为什么彻底不存在
 *
 * `BuildConfig.DEBUG` 在 AGP 生成的 `BuildConfig` 里是 `public static final boolean`，
 * 也就是**编译期常量**。release 构建里它是 `false`，
 * `if (!BuildConfig.DEBUG || !visible) return` 会被编译器折叠成一个无条件的 `return`，
 * [TunerBody] 随之变成不可达代码，R8 再把整个调参页（滑块、HSV 换算、剪贴板调用）
 * 整段消除。
 *
 * 这也是当初选 `BuildConfig.DEBUG` 而不是运行时 `ApplicationInfo.FLAG_DEBUGGABLE` 的原因：
 * 后者编译器看不见，调参页的代码会一直留在正式包里。
 *
 * ## 状态是全 hoist 的
 *
 * [tokens] 由调用方持有、[onTokensChange] 往上抛。调用方把它塞进 `ProvideGlassTokens`，
 * 全应用的玻璃控件就跟着一起变——调参页不需要知道有哪些控件。
 *
 * @param visible 由调用方控制显隐。release 构建里传 `true` 也无效。
 * @param onDismiss 收起调参页。注意收起只是隐藏，参数值仍在调用方手里。
 */
@Composable
fun TunerScreen(
    visible: Boolean,
    tokens: GlassTokens,
    onTokensChange: (GlassTokens) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {}
) {
    // 这一行就是整个「仅 debug 可见」的实现。
    if (!BuildConfig.DEBUG || !visible) return
    TunerBody(
        tokens = tokens,
        onTokensChange = onTokensChange,
        backdrop = backdrop,
        onDismiss = onDismiss,
        modifier = modifier
    )
}

/**
 * 调参页的**自带状态入口**：一个触发器 + 一整块调参浮层，调用方什么都不用管。
 *
 * 它自己持有 token（初始值 [initialTokens]）并在自己的子树里用 `ProvideGlassTokens` 提供，
 * 所以触发器、浮层、浮层里的示例区都会实时跟着滑块变。
 *
 * ## 放在哪里
 *
 * **必须放在屏幕根部的 `Box` 里，和其它全屏浮层同一层。** 调参页是整屏浮层，
 * 需要整屏的父约束；塞进列表项或设置页的某一行里，它只能撑开那一行的高度，
 * 整个面板会被压扁。（Compose 的子节点无法绘制/命中到自己父约束之外的区域。）
 *
 * ```kotlin
 * Box(Modifier.fillMaxSize()) {
 *     Wallpaper()
 *     SettingsContent()
 *     TunerEntryPoint(backdrop)      // 根部同一层，放最后
 * }
 * ```
 *
 * ## 和 [TunerScreen] 的分工
 *
 * [TunerEntryPoint] 只影响它自己子树里的玻璃控件——适合「调出满意的数值然后复制走」。
 * 如果你想让**整个应用**（包括浮层之外的所有屏幕）跟着滑块实时变，就用 [TunerScreen]，
 * 把 token 提到根节点持有，再 `ProvideGlassTokens` 包住整个应用：
 *
 * ```kotlin
 * var tokens by remember { mutableStateOf(GlassTokens.Dark) }
 * ProvideGlassTokens(tokens) {
 *     AppContent()
 *     TunerScreen(visible, tokens, { tokens = it }, backdrop)
 * }
 * ```
 */
@Composable
fun TunerEntryPoint(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    initialTokens: GlassTokens = GlassTokens.Dark
) {
    if (!BuildConfig.DEBUG) return

    var tokens by remember { mutableStateOf(initialTokens) }
    var visible by remember { mutableStateOf(false) }

    ProvideGlassTokens(tokens) {
        Box(modifier.fillMaxSize()) {
            GlassButton(
                onClick = { visible = true },
                backdrop = backdrop,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                TunerText("玻璃调参")
            }

            TunerScreen(
                visible = visible,
                tokens = tokens,
                onTokensChange = { tokens = it },
                backdrop = backdrop,
                onDismiss = { visible = false }
            )
        }
    }
}

@Composable
private fun TunerBody(
    tokens: GlassTokens,
    onTokensChange: (GlassTokens) -> Unit,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var justCopied by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var switchSample by remember { mutableStateOf(false) }
    var tabIndex by remember { mutableIntStateOf(0) }
    var intensity by remember { mutableFloatStateOf(0.6f) }

    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(1600)
            justCopied = false
        }
    }

    Box(modifier.fillMaxSize()) {
        // 遮罩：故意不响应点击。调参时误触关掉会把刚调好的参数弄丢。
        Box(
            Modifier
                .fillMaxSize()
                .background(Colour.BackgroundTop.copy(alpha = 0.55f))
        )

        GlassPanel(
            backdrop = backdrop,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
            shape = RoundedRectangle(28.dp)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Header(
                    copied = justCopied,
                    backdrop = backdrop,
                    onCopy = {
                        copyToClipboard(context, tokens.toKotlinSource())
                        justCopied = true
                    },
                    onDismiss = onDismiss
                )

                Spacer(Modifier.height(14.dp))

                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    SectionTitle("折射")
                    DpSlider(
                        label = "折射高度",
                        value = tokens.refractionHeight.value,
                        backdrop = backdrop,
                        onValueChange = { onTokensChange(tokens.copy(refractionHeight = it.dp)) }
                    )
                    DpSlider(
                        label = "折射量",
                        value = tokens.refractionAmount.value,
                        backdrop = backdrop,
                        onValueChange = { onTokensChange(tokens.copy(refractionAmount = it.dp)) }
                    )

                    SectionTitle("模糊与形状")
                    DpSlider(
                        label = "模糊半径",
                        value = tokens.blurRadius.value,
                        backdrop = backdrop,
                        onValueChange = { onTokensChange(tokens.copy(blurRadius = it.dp)) }
                    )
                    DpSlider(
                        label = "圆角",
                        value = tokens.cornerRadius.value,
                        backdrop = backdrop,
                        onValueChange = { onTokensChange(tokens.copy(cornerRadius = it.dp)) }
                    )

                    SectionTitle("表面色")
                    SurfaceColourEditor(
                        tokens = tokens,
                        backdrop = backdrop,
                        onTokensChange = onTokensChange
                    )

                    SectionTitle("降级")
                    SwitchRow(
                        label = "色散",
                        hint = "需要 Android 13+；低于此版本会自动跳过",
                        checked = tokens.chromaticAberration,
                        backdrop = backdrop,
                        onCheckedChange = { onTokensChange(tokens.copy(chromaticAberration = it)) }
                    )
                    SwitchRow(
                        label = "强制低端档",
                        hint = "当前：${tokens.tier.label()}",
                        checked = tokens.forceLowEnd,
                        backdrop = backdrop,
                        onCheckedChange = { onTokensChange(tokens.copy(forceLowEnd = it)) }
                    )

                    SectionTitle("示例")
                    GlassCard(backdrop = backdrop) {
                        TunerText("这是一张玻璃卡片。")
                        Spacer(Modifier.height(6.dp))
                        TunerText(
                            "低对比、安静，不靠渐变吸引注意。",
                            color = LocalGlassContentColor.current.copy(alpha = 0.6f)
                        )
                    }
                    Column(Modifier.fillMaxWidth()) {
                        SliderLabel("强度", formatFloat(intensity))
                        GlassSlider(
                            value = intensity,
                            onValueChange = { intensity = it },
                            backdrop = backdrop,
                            steps = 4
                        )
                    }
                    SwitchRow(
                        label = "开关",
                        hint = "轻点任意位置切换；从旋钮上可以拖",
                        checked = switchSample,
                        backdrop = backdrop,
                        onCheckedChange = { switchSample = it }
                    )
                    GlassButton(onClick = { showDialog = true }, backdrop = backdrop) {
                        TunerText("打开对话框")
                    }
                    GlassTabBar(
                        selectedIndex = tabIndex,
                        onTabSelected = { tabIndex = it },
                        backdrop = backdrop,
                        tabCount = 3
                    ) {
                        SampleTab("记录", tabIndex == 0) { tabIndex = 0 }
                        SampleTab("统计", tabIndex == 1) { tabIndex = 1 }
                        SampleTab("设置", tabIndex == 2) { tabIndex = 2 }
                    }

                    Spacer(Modifier.height(4.dp))

                    SectionTitle("导出的参数")
                    TunerText(
                        text = tokens.toKotlinSource(),
                        style = TunerTextStyle.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 15.sp
                        ),
                        color = LocalGlassContentColor.current.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(28.dp))
                }
            }
        }

        GlassDialog(
            visible = showDialog,
            onDismissRequest = { showDialog = false },
            backdrop = backdrop
        ) {
            TunerText("对话框也是玻璃", style = TunerTextStyle.copy(fontWeight = FontWeight.Medium))
            Spacer(Modifier.height(10.dp))
            TunerText(
                "它没有开新的 Window：backdrop 要靠同一棵 LayoutCoordinates 树算坐标，" +
                        "跨 Window 之后采样会失效。",
                color = LocalGlassContentColor.current.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(16.dp))
            GlassButton(onClick = { showDialog = false }, backdrop = backdrop) {
                TunerText("知道了")
            }
        }
    }
}

@Composable
private fun Header(
    copied: Boolean,
    backdrop: Backdrop,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            TunerText("玻璃调参", style = TunerTextStyle.copy(fontWeight = FontWeight.SemiBold))
            TunerText(
                "debug 构建专用，release 包里不存在",
                style = TunerTextStyle.copy(fontSize = 11.sp),
                color = LocalGlassContentColor.current.copy(alpha = 0.55f)
            )
        }
        GlassButton(onClick = onCopy, backdrop = backdrop) {
            TunerText(if (copied) "已复制" else "复制参数")
        }
        Spacer(Modifier.width(8.dp))
        GlassButton(onClick = onDismiss, backdrop = backdrop) {
            TunerText("收起")
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    TunerText(
        text = text,
        style = TunerTextStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        color = LocalGlassContentColor.current.copy(alpha = 0.6f)
    )
}

@Composable
private fun DpSlider(
    label: String,
    value: Float,
    backdrop: Backdrop,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        SliderLabel(label, "${formatFloat(value)} dp")
        GlassSlider(
            value = value,
            onValueChange = onValueChange,
            backdrop = backdrop,
            valueRange = 0f..40f
        )
    }
}

@Composable
private fun SliderLabel(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        TunerText(label)
        Spacer(Modifier.weight(1f))
        TunerText(
            value,
            style = TunerTextStyle.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            color = LocalGlassContentColor.current.copy(alpha = 0.65f)
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    hint: String,
    checked: Boolean,
    backdrop: Backdrop,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            TunerText(label)
            TunerText(
                hint,
                style = TunerTextStyle.copy(fontSize = 11.sp),
                color = LocalGlassContentColor.current.copy(alpha = 0.55f)
            )
        }
        GlassToggle(
            selected = checked,
            onSelect = onCheckedChange,
            backdrop = backdrop
        )
    }
}

/**
 * 表面色编辑器：H / S / V / A 四个滑块 + 一块实时色板。
 *
 * 拆成四个滑块而不是一个取色器，是因为这里调的是**一层薄膜的颜色**：
 * 真实动作是「色相往暖处挪一点」「不透明度再压下去一点」，
 * 而不是从色轮里挑一个颜色出来。
 */
@Composable
private fun SurfaceColourEditor(
    tokens: GlassTokens,
    backdrop: Backdrop,
    onTokensChange: (GlassTokens) -> Unit
) {
    val surface = tokens.surfaceColor
    val hsv = remember(surface) {
        FloatArray(3).also { AndroidColor.colorToHSV(surface.toArgb(), it) }
    }

    fun apply(newHsv: FloatArray, alpha: Float) {
        val colour = Color(AndroidColor.HSVToColor(newHsv)).copy(alpha = alpha)
        onTokensChange(tokens.copy(surfaceColor = colour))
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(surface)
            )
            Spacer(Modifier.width(10.dp))
            TunerText(
                surface.toHexLiteral(),
                style = TunerTextStyle.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                color = LocalGlassContentColor.current.copy(alpha = 0.65f)
            )
        }

        ColourSlider(
            label = "色相",
            value = hsv[0],
            range = 0f..360f,
            backdrop = backdrop
        ) { apply(floatArrayOf(it, hsv[1], hsv[2]), surface.alpha) }

        ColourSlider(
            label = "饱和度",
            value = hsv[1],
            range = 0f..1f,
            backdrop = backdrop
        ) { apply(floatArrayOf(hsv[0], it, hsv[2]), surface.alpha) }

        ColourSlider(
            label = "明度",
            value = hsv[2],
            range = 0f..1f,
            backdrop = backdrop
        ) { apply(floatArrayOf(hsv[0], hsv[1], it), surface.alpha) }

        ColourSlider(
            label = "不透明度",
            value = surface.alpha,
            range = 0f..1f,
            backdrop = backdrop
        ) { apply(hsv, it) }
    }
}

@Composable
private fun ColourSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    backdrop: Backdrop,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        SliderLabel(label, formatFloat(value))
        GlassSlider(
            value = value,
            onValueChange = onValueChange,
            backdrop = backdrop,
            valueRange = range
        )
    }
}

@Composable
private fun RowScope.SampleTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    GlassTab(selected = selected, onClick = onClick) {
        TunerText(label, style = TunerTextStyle.copy(fontSize = 12.sp))
    }
}

@Composable
private fun TunerText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TunerTextStyle,
    color: Color = LocalGlassContentColor.current
) {
    // 玻璃组件都会提供 LocalGlassContentColor；万一在玻璃之外用，退回主文字色。
    val resolved = if (color == Color.Unspecified) Colour.InkOnDark else color
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = resolved)
    )
}

private val TunerTextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)

private fun GlassTier.label(): String = when (this) {
    GlassTier.Translucent -> "只有半透明色（≤ Android 11）"
    GlassTier.BlurVibrancy -> "有模糊、没折射（Android 12）"
    GlassTier.Refraction -> "完整折射（Android 13+）"
}

private fun formatFloat(value: Float): String = String.format(Locale.ROOT, "%.2f", value)

private fun Color.toHexLiteral(): String =
    String.format(Locale.ROOT, "#%08X", toArgb())

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("GlassTokens", text))
}

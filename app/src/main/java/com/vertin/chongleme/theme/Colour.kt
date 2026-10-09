package com.vertin.chongleme.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 全应用的色彩常量。
 *
 * 设计立场：这是一个夜间使用的私人记录本，基调是暗的、低对比的、安静的。
 * 背景必须是「有内容」的渐变而非纯色——因为液体玻璃折射的正是背后的东西，
 * 纯色背景上玻璃是看不见的。
 */
@Immutable
object Colour {
    /** 背景渐变顶部：接近纯黑的蓝灰 */
    val BackgroundTop = Color(0xFF0B0D12)

    /** 背景渐变底部：稍微抬亮一点点，让屏幕下半部不显得死黑 */
    val BackgroundBottom = Color(0xFF151A24)

    /** 暖色光斑：给深夜底子一点体温，避免整屏是冷调的科技感 */
    val GlowWarm = Color(0xFFE8863C)

    /** 冷色光斑：与暖斑形成微弱的冷暖对峙 */
    val GlowCool = Color(0xFF3C8CE8)

    /** 玻璃表面叠加色（浅色模式） */
    val SurfaceLight = Color(0xFFFAFAFA)

    /** 玻璃表面叠加色（深色模式） */
    val SurfaceDark = Color(0xFF121212)

    /** 强调色：浅色模式 */
    val AccentLight = Color(0xFF0088FF)

    /** 强调色：深色模式 */
    val AccentDark = Color(0xFF0091FF)

    /** 主要文字（深色模式下的前景） */
    val InkOnDark = Color(0xFFF2F4F8)

    /** 次要文字 */
    val InkMutedOnDark = Color(0x99F2F4F8)

    /** 主要文字（浅色模式下的前景） */
    val InkOnLight = Color(0xFF101318)

    /** 次要文字（浅色模式） */
    val InkMutedOnLight = Color(0x99101318)

    /** 强度点阵的填充色：用暖橙，与光斑同色系 */
    val IntensityOn = GlowWarm

    /** 强度点阵的未填充色 */
    val IntensityOff = Color(0x33FFFFFF)
}

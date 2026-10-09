package com.vertin.chongleme.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.kyant.backdrop.Backdrop
import com.vertin.chongleme.data.Intensity
import com.vertin.chongleme.data.Mood
import com.vertin.chongleme.data.Photo
import com.vertin.chongleme.data.PhotoDraft
import com.vertin.chongleme.theme.Colour
import com.vertin.chongleme.theme.LocalGlassContentColor
import com.vertin.chongleme.ui.LocalPhotoStore
import com.vertin.chongleme.ui.displayName
import com.vertin.chongleme.ui.glass.GlassButton
import com.vertin.chongleme.ui.glass.GlassCard
import com.vertin.chongleme.ui.glass.GlassSlider
import com.vertin.chongleme.ui.glass.rememberGlassHaptics
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * 编辑页的各个段落。
 *
 * 拆成一组小 composable 而不是写成一个巨大的 Column：每段只吃自己需要的参数，
 * 于是「改了备注会不会让强度重画」这类问题在类型上就有答案。
 *
 * 视觉上只有两条规则：
 * - **段落是玻璃卡**（[GlassCard]），和今天页的记录卡同一套语言；
 * - **选项是普通胶囊**，不套玻璃。一屏十来个玻璃节点会让背板采样成为主要开销，
 *   而选项本身不需要折射——只有整块的容器才需要「浮在壁纸上」的感觉。
 */

private val SectionTitleStyle = TextStyle(
    color = Colour.InkMutedOnDark,
    fontSize = 12.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 1.sp,
)

private val BodyStyle = TextStyle(color = Colour.InkOnDark, fontSize = 15.sp, lineHeight = 22.sp)

private val NumberStyle = TextStyle(
    color = Colour.InkOnDark,
    fontSize = 20.sp,
    fontFamily = FontFamily.Monospace,
)

private val HintStyle = TextStyle(color = Colour.InkMutedOnDark, fontSize = 12.sp, lineHeight = 18.sp)

private val ChipTextStyle = TextStyle(fontSize = 13.sp)

/** 缩略图边长。 */
private val ThumbSize = 84.dp

/** 一条记录的配图上限（也见 `EditorScreen.MaxPhotos`）。 */
internal const val MaxPhotosPerEntry: Int = 9

/** 编辑页顶部：左边一个「取消」，右边是这一页在干什么。 */
@Composable
internal fun EditorHeader(
    title: String,
    backdrop: Backdrop,
    onCancel: () -> Unit,
    cancelEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassButton(onClick = onCancel, backdrop = backdrop, enabled = cancelEnabled) {
            BasicText("取消", style = TextStyle(color = LocalGlassContentColor.current, fontSize = 14.sp))
        }
        Spacer(Modifier.width(14.dp))
        BasicText(
            text = title,
            style = TextStyle(
                color = Colour.InkOnDark,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.3).sp,
            ),
        )
    }
}

/**
 * 时间：两个可点的值 + 一个「现在」。
 *
 * 「弹哪个选择器」这件事收在这一段里：调用方只关心「用户挑了哪一天 / 哪个时刻」，
 * 不关心系统对话框长什么样。
 */
@Composable
internal fun EditorTimeSection(
    occurredAt: Long,
    today: LocalDate,
    onPickDate: (LocalDate) -> Unit,
    onPickTime: (LocalTime) -> Unit,
    onNow: () -> Unit,
    backdrop: Backdrop,
) {
    val context = LocalContext.current
    EditorSection(title = "时间", backdrop = backdrop) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ValueChip(
                text = dateLabelOf(occurredAt, today),
                onClick = { openDatePicker(context, occurredAt, onPickDate) },
            )
            ValueChip(
                text = clockLabelOf(occurredAt),
                monospace = true,
                onClick = { openTimePicker(context, occurredAt, onPickTime) },
            )
            Spacer(Modifier.weight(1f))
            PlainChip(text = "现在", onClick = onNow)
        }
    }
}

/** 多久：一个只收数字的输入框 + 四个常用档位。 */
@Composable
internal fun EditorDurationSection(
    text: String,
    onTextChange: (String) -> Unit,
    backdrop: Backdrop,
) {
    EditorSection(title = "多久", backdrop = backdrop) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = text,
                // 只留数字、最多四位：输入法类型只是建议，粘贴照样能塞进任何字符。
                onValueChange = { raw -> onTextChange(raw.filter { it.isDigit() }.take(4)) },
                singleLine = true,
                textStyle = NumberStyle,
                cursorBrush = SolidColor(Colour.IntensityOn),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(88.dp),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) {
                            BasicText("—", style = NumberStyle.copy(color = Colour.IntensityOff))
                        }
                        innerTextField()
                    }
                },
            )
            Spacer(Modifier.width(6.dp))
            BasicText("分钟", style = BodyStyle)
            Spacer(Modifier.weight(1f))
            BasicText("不记得就空着", style = HintStyle)
        }

        Spacer(Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuickMinutes.forEach { minutes ->
                val preset = minutes.toString()
                ChoiceChip(
                    text = "${minutes}分",
                    selected = text == preset,
                    onClick = { onTextChange(if (text == preset) "" else preset) },
                )
            }
        }
    }
}

/** 强度：点阵给结论，滑块给输入。 */
@Composable
internal fun EditorIntensitySection(
    value: Int,
    onChange: (Int) -> Unit,
    backdrop: Backdrop,
) {
    EditorSection(title = "强度", backdrop = backdrop, trailing = "$value / ${Intensity.MAX}") {
        IntensityMarks(value)

        Spacer(Modifier.height(4.dp))

        GlassSlider(
            value = value.toFloat(),
            onValueChange = { raw -> onChange(intensityOf(raw)) },
            backdrop = backdrop,
            valueRange = Intensity.MIN.toFloat()..Intensity.MAX.toFloat(),
            // 注意：GlassSlider 的 steps 是「中间间隔数」而不是 Material Slider 的「可选值个数」，
            // 它自己算的是 (fraction * steps).roundToInt() / steps。1..5 要 5 个位置 → steps = 4。
            steps = Intensity.MAX - Intensity.MIN,
        )
    }
}

/**
 * 心情：五个胶囊，单选，再点一下取消。
 *
 * 「可以空着」是有意的：心情不是必填项，用户不想标注的时候不该被拦着。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditorMoodSection(
    mood: Mood?,
    onSelect: (Mood?) -> Unit,
    backdrop: Backdrop,
) {
    EditorSection(title = "心情", backdrop = backdrop) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Mood.entries.forEach { item ->
                ChoiceChip(
                    text = item.displayName,
                    selected = item == mood,
                    // 再点一下已选中的就是取消选择，不需要额外做一个「清除」按钮。
                    onClick = { onSelect(if (item == mood) null else item) },
                )
            }
        }

        Spacer(Modifier.height(2.dp))

        BasicText(
            text = if (mood == null) "可以空着" else "再点一下取消",
            style = HintStyle,
        )
    }
}

/** 备注：一个长得像纸的输入框。 */
@Composable
internal fun EditorNoteSection(
    note: String,
    onNoteChange: (String) -> Unit,
    backdrop: Backdrop,
) {
    EditorSection(title = "备注", backdrop = backdrop) {
        BasicTextField(
            value = note,
            onValueChange = onNoteChange,
            textStyle = BodyStyle,
            cursorBrush = SolidColor(Colour.IntensityOn),
            minLines = 3,
            maxLines = 8,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 84.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (note.isEmpty()) {
                        BasicText("写点什么。只有你自己看得到。", style = BodyStyle.copy(color = Colour.InkMutedOnDark))
                    }
                    innerTextField()
                }
            },
        )
    }
}

/**
 * 配图：横排缩略图 + 拍照 / 相册两个入口。
 *
 * 两个入口的权限成本完全不同，这一点直接写在按钮的可用状态上：
 * 拍照要 CAMERA 运行时权限，相册走系统 Photo Picker，**一个权限都不用**。
 */
@Composable
internal fun EditorPhotoSection(
    existing: List<Photo>,
    pending: List<PhotoDraft>,
    importing: Boolean,
    problem: String?,
    backdrop: Backdrop,
    onCapture: () -> Unit,
    onPick: () -> Unit,
    onRemoveExisting: (Photo) -> Unit,
    onRemovePending: (PhotoDraft) -> Unit,
) {
    val store = LocalPhotoStore.current
    val count = existing.size + pending.size
    val full = count >= MaxPhotosPerEntry

    EditorSection(
        title = "配图",
        backdrop = backdrop,
        trailing = if (count == 0) null else "$count / $MaxPhotosPerEntry",
    ) {
        if (count > 0) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(existing, key = { "photo-${it.id}" }) { photo ->
                    PhotoThumb(
                        model = remember(photo.relPath) { store.resolve(photo.relPath) },
                        onRemove = { onRemoveExisting(photo) },
                    )
                }
                items(pending, key = { "draft-${it.relPath}" }) { draft ->
                    PhotoThumb(
                        model = remember(draft.relPath) { store.resolve(draft.relPath) },
                        onRemove = { onRemovePending(draft) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(
                onClick = onCapture,
                backdrop = backdrop,
                enabled = !full && !importing,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                BasicText("拍照", style = ChipTextStyle.copy(color = LocalGlassContentColor.current))
            }
            GlassButton(
                onClick = onPick,
                backdrop = backdrop,
                enabled = !full && !importing,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                BasicText("从相册选", style = ChipTextStyle.copy(color = LocalGlassContentColor.current))
            }
        }

        Spacer(Modifier.height(8.dp))

        val line = when {
            importing -> "正在处理照片…"
            problem != null -> problem
            full -> "一条记录最多 $MaxPhotosPerEntry 张，够了。"
            count == 0 -> "照片只留在这台设备上，应用连网络权限都没有。"
            else -> null
        }
        if (line != null) {
            BasicText(text = line, style = HintStyle)
        }
    }
}

/** 段落容器：一块玻璃卡，标题在左上，右上可以挂一句状态。 */
@Composable
private fun EditorSection(
    title: String,
    backdrop: Backdrop,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(
        backdrop = backdrop,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(title, style = SectionTitleStyle)
            if (trailing != null) {
                Spacer(Modifier.weight(1f))
                BasicText(
                    text = trailing,
                    style = SectionTitleStyle.copy(
                        fontFamily = FontFamily.Monospace,
                        color = Colour.InkOnDark,
                    ),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/**
 * 选项胶囊。
 *
 * 触摸区是外层 48dp 的盒子、视觉只是里面那颗小胶囊——这是 Material 的做法，
 * 也是「一排胶囊看起来不笨重、手指又点得中」的唯一办法。
 */
@Composable
private fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ChipSurface(
        onClick = onClick,
        modifier = modifier,
        background = if (selected) Colour.IntensityOn.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.07f),
    ) {
        BasicText(
            text = text,
            style = ChipTextStyle.copy(
                color = if (selected) Colour.InkOnDark else Colour.InkMutedOnDark,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
        )
    }
}

/** 展示一个值、点一下能改它的胶囊（时间、日期）。 */
@Composable
private fun ValueChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
) {
    ChipSurface(
        onClick = onClick,
        modifier = modifier,
        background = Color.White.copy(alpha = 0.11f),
    ) {
        BasicText(
            text = text,
            style = ChipTextStyle.copy(
                color = Colour.InkOnDark,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            ),
        )
    }
}

/** 低强调的胶囊（「现在」这类一次性动作）。 */
@Composable
private fun PlainChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ChipSurface(onClick = onClick, modifier = modifier, background = Color.Transparent) {
        BasicText(text = text, style = ChipTextStyle.copy(color = Colour.InkMutedOnDark))
    }
}

@Composable
private fun ChipSurface(
    onClick: () -> Unit,
    background: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val haptics = rememberGlassHaptics()
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) {
                haptics.tap()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(background)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            content()
        }
    }
}

/** 强度点阵。和今天页记录卡上的点阵同一套语言：点只表示「几」，不表示「好不好」。 */
@Composable
private fun IntensityMarks(value: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Intensity.range.forEach { level ->
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (level <= value) Colour.IntensityOn else Colour.IntensityOff)
            )
        }
    }
}

@Composable
private fun PhotoThumb(model: Any, onRemove: () -> Unit) {
    val haptics = rememberGlassHaptics()
    Box(Modifier.size(ThumbSize)) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.06f)),
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                // 触摸区 40dp、视觉圆点 24dp：图标可以小，手指不行。
                .size(40.dp)
                .clickable(onClickLabel = "删掉这张配图", role = Role.Button) {
                    haptics.tap()
                    onRemove()
                },
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp, end = 4.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("×", style = TextStyle(color = Colour.InkOnDark, fontSize = 15.sp))
            }
        }
    }
}

/** 时长快捷档位。四个就够：再多的档位等于让用户重新做一次选择。 */
private val QuickMinutes = listOf(15, 30, 45, 60)

/**
 * 滑块的浮点值 → 整数强度。
 *
 * 按「落在第几档」换算，而不是对值本身四舍五入：档位是等分的，中间档算出来的浮点数
 * 往往不是整数（比如 2.333），直接 round 会跳过某些等级。这样写之后 1..5 每一档都点得出来。
 */
private fun intensityOf(raw: Float): Int {
    val span = (Intensity.MAX - Intensity.MIN).toFloat()
    val fraction = ((raw - Intensity.MIN) / span).coerceIn(0f, 1f)
    return Intensity.coerce((fraction * span).roundToInt() + Intensity.MIN)
}

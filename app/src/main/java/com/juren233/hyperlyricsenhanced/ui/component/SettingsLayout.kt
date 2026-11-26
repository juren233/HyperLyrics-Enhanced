/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.style.TextOverflow
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Constraints
import android.icu.text.BreakIterator
import java.util.Locale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 设置页共用的布局尺寸与组件：卡片外边距、超级岛图形、提示卡片、头卡与磁贴。
 * 主页与 Apple Music 体验优化页都从这里取值，不在页面里另写数值。
 */

private val CardHorizontalMargin = 12.dp
private val CardBottomMargin = 12.dp
/** 页面大标题与第一张头卡之间的间距，主页与 Apple Music 体验优化页共用。 */
private val HeroCardTopMargin = 12.dp

/** 卡片在列表中的外边距：左右 12dp，与下一项之间 12dp。 */
fun Modifier.settingsCard(): Modifier =
    padding(horizontal = CardHorizontalMargin)
        .padding(bottom = CardBottomMargin)
        .fillMaxWidth()

/** 页面顶部头卡的外边距：在 [settingsCard] 之上，与页面大标题之间留 [HeroCardTopMargin]。 */
fun Modifier.settingsHeroCard(): Modifier = padding(top = HeroCardTopMargin).settingsCard()

/**
 * 超级岛图形：胶囊外框 + 左侧封面圆点 + 右侧歌词短条，与 App 图标的骨架语义一致。
 * Miuix 图标库没有胶囊形状，这里按 Miuix 图标的线宽比例绘制，尺寸由 [modifier] 决定。
 */
@Composable
fun IslandGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val unit = size.width / 24f
        val stroke = 1.6f * unit
        val top = 6.5f * unit
        val height = 11f * unit
        drawRoundRect(
            color = color,
            topLeft = Offset(1.5f * unit, top),
            size = Size(21f * unit, height),
            cornerRadius = CornerRadius(height / 2f),
            style = Stroke(width = stroke),
        )
        drawCircle(color = color, radius = 2.4f * unit, center = Offset(7f * unit, 12f * unit))
        drawLine(
            color = color,
            start = Offset(11.5f * unit, 12f * unit),
            end = Offset(17.5f * unit, 12f * unit),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** Miuix 图标的线宽比例（24 视口下 1.6），自绘图标统一使用。 */
private const val MiuixGlyphStroke = 1.6f

private fun miuixStrokeGlyph(name: String, strokePath: PathBuilder.() -> Unit, dotY: Float): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = MiuixGlyphStroke,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = strokePath,
        )
        // 感叹号的圆点。
        .path(fill = SolidColor(Color.Black)) {
            moveTo(11f, dotY)
            arcToRelative(1f, 1f, 0f, true, true, 2f, 0f)
            arcToRelative(1f, 1f, 0f, true, true, -2f, 0f)
            close()
        }
        .build()

/**
 * 状态图标：Miuix 图标库没有警告与错误图标，按 Miuix 线宽自绘。
 * [Warning] 是圆角三角形加感叹号，[Error] 是圆形加感叹号。
 */
object StatusGlyphs {
    val Warning: ImageVector by lazy {
        miuixStrokeGlyph("StatusWarning", {
            // 三角形顶点 (12,3)、(22,20.5)、(2,20.5)，三个角各从顶点沿两条边退 4 个单位、以二次曲线圆过去，
            // 与日志分析小图标的圆润三角形同一圆角比例（约为边长的五分之一）。
            moveTo(13.984f, 6.472f)
            lineTo(20.016f, 17.028f)
            quadTo(22f, 20.5f, 18f, 20.5f)
            lineTo(6f, 20.5f)
            quadTo(2f, 20.5f, 3.984f, 17.028f)
            lineTo(10.016f, 6.472f)
            quadTo(12f, 3f, 13.984f, 6.472f)
            close()
            // 感叹号居中：圆点底部到底边内侧、竖线顶端到两条斜边内侧，留白都约 1.9 个单位。
            moveTo(12f, 9.3f)
            lineTo(12f, 13.6f)
        }, dotY = 16.8f)
    }

    val Error: ImageVector by lazy {
        miuixStrokeGlyph("StatusError", {
            moveTo(2.75f, 12f)
            arcToRelative(9.25f, 9.25f, 0f, true, true, 18.5f, 0f)
            arcToRelative(9.25f, 9.25f, 0f, true, true, -18.5f, 0f)
            close()
            moveTo(12f, 7.3f)
            lineTo(12f, 12.6f)
        }, dotY = 16.2f)
    }
}

/**
 * 信息提示卡片：浅色底 + 图标 + 文字。[emphasized] 时文字加大加粗，与 [WarningNoticeCard] 同字号。
 * 配色与大磁贴、主页头卡同一套主题色（[HeroTone.Accent]）：底色取浅色底，图标与文字都用该色调的文字色。
 */
@Composable
fun NoticeCard(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val colors = noticeCardColors()
    NoticeCardLayout(text, icon, colors.container, colors.icon, colors.text, emphasized, modifier)
}

/** [NoticeCard] 的三种颜色：底色、图标色、文字色。 */
internal data class NoticeCardColors(val container: Color, val icon: Color, val text: Color)

@Composable
internal fun noticeCardColors(): NoticeCardColors {
    val palette = heroPalette(HeroTone.Accent)
    return NoticeCardColors(container = palette.container, icon = palette.content, text = palette.content)
}

/**
 * 警告横幅：与 [NoticeCard] 同一布局，配色取头卡的 [HeroTone.Warning] 浅色底，图标为 [StatusGlyphs.Warning]；
 * 图标与文字都用该色调的文字色，与已开启磁贴的文字配色方式一致；文字加大加粗（与头卡副标题同字号）。
 */
@Composable
fun WarningNoticeCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    val palette = heroPalette(HeroTone.Warning)
    NoticeCardLayout(text, StatusGlyphs.Warning, palette.container, palette.content, palette.content, emphasized = true, modifier = modifier)
}

@Composable
private fun NoticeCardLayout(
    text: String,
    icon: ImageVector,
    container: Color,
    iconTint: Color,
    textColor: Color,
    emphasized: Boolean,
    modifier: Modifier,
) {
    Card(modifier = modifier, colors = CardDefaults.defaultColors(color = container)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(22.dp),
            )
            Text(
                text = text,
                fontSize = if (emphasized) 14.sp else 13.sp,
                lineHeight = if (emphasized) 19.sp else 18.sp,
                fontWeight = if (emphasized) FontWeight.Medium else null,
                color = textColor,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---- 首页风格的头卡与磁贴 ----

/** 状态切换（磁贴开关、头卡状态）的过渡，与项目其余状态动画一致（300ms FastOutSlowIn）。 */
private fun <T> stateChangeTween() = tween<T>(durationMillis = 300, easing = FastOutSlowInEasing)
/** 可点击大卡片的按压反馈（按下处所在的角向下倾斜）：首启流程的选项卡片、头卡与磁贴共用。 */
val CardPressFeedback = PressFeedbackType.Tilt
private val TileGap = 12.dp
private val TileHeight = 112.dp
private val TileIconSize = 26.dp
private val TileInsideMargin = 14.dp

/**
 * 头卡的色调：[Accent] 取主题色（主页）；[Success]、[Warning]、[Error] 取对应状态色。
 * 四种色调都按 [tonalPalette] 配成浅色底。
 */
enum class HeroTone { Accent, Success, Warning, Error }

/** 头卡最小高度。 */
private val HeroMinHeight = 124.dp

/** 右下角装饰图标相对卡片最小高度的比例：124dp 高时图标 124dp、向右下偏出 22dp / 26dp。 */
private const val HeroDecorationScale = 1f
private const val HeroDecorationOffsetX = 22f / 124f
private const val HeroDecorationOffsetY = 26f / 124f

private val HeroSuccessHue = Color(0xFF2DB55D)
private val HeroWarningHue = Color(0xFFF2A516)
private val HeroErrorHue = Color(0xFFE5484D)

internal class TonalPalette(
    val container: Color,
    val content: Color,
    val secondary: Color,
    val decoration: Color,
    /** 状态小图标里符号（感叹号、勾）的颜色：浅色下比 [content] 浅一档，深色下与 [content] 相同。 */
    val glyphInk: Color = content,
)

/** 浅色下状态图标符号向黑色混入的比例：文字色用 0.45，符号用 0.30 浅一档，在图标的浅色底上对比度仍不低于 3:1。 */
internal const val GlyphInkLightDarken = 0.30f

/**
 * 浅色底配色，头卡与已开启的磁贴共用：底色由 [hue] 以低透明度叠在卡片底色上得到，
 * 文字取 [hue] 的加深（浅色模式）或提亮（深色模式），随深浅色主题自动适配。
 */
@Composable
private fun tonalPalette(hue: Color): TonalPalette {
    val colors = MiuixTheme.colorScheme
    // 按当前卡片底色判断深浅，App 可强制深/浅色，不能只看系统设置。
    val dark = colors.surfaceContainer.luminance() < 0.5f
    val content = lerp(hue, if (dark) Color.White else Color.Black, if (dark) 0.35f else 0.45f)
    return TonalPalette(
        container = hue.copy(alpha = if (dark) 0.18f else 0.13f).compositeOver(colors.surfaceContainer),
        content = content,
        secondary = content.copy(alpha = 0.75f),
        decoration = hue.copy(alpha = if (dark) 0.24f else 0.3f),
        glyphInk = if (dark) content else lerp(hue, Color.Black, GlyphInkLightDarken),
    )
}

@Composable
internal fun heroPalette(tone: HeroTone): TonalPalette = tonalPalette(
    when (tone) {
        HeroTone.Accent -> MiuixTheme.colorScheme.primary
        HeroTone.Success -> HeroSuccessHue
        HeroTone.Warning -> HeroWarningHue
        HeroTone.Error -> HeroErrorHue
    }
)

/**
 * 状态切换的颜色过渡权重：[states] 里每个状态一份 0–1 的权重，当前 [state] 为 1、其余为 0，状态变化时按 300ms 过渡。
 * 调用方把每个状态「按当前主题现取」的颜色交给 [blendColors] 按权重混合。
 *
 * 不用 animateColorAsState：它对目标色做补间，切换深浅色或强调色时目标色变了，也会被补间 300ms，
 * 结果页面背景（直接取主题色）立刻变了，卡片底色却慢半拍才跟上。这里只对状态本身做动画，
 * 主题变化时两端颜色在同一帧换成新主题的颜色，与页面背景同步。
 */
@Composable
internal fun <S> animateStateWeights(state: S, states: List<S>, label: String): List<Float> =
    states.map { option ->
        key(option) {
            animateFloatAsState(
                targetValue = if (option == state) 1f else 0f,
                animationSpec = stateChangeTween(),
                label = label,
            ).value
        }
    }

/** 按权重混合颜色（sRGB 分量加权平均）；某一项权重为 1、其余为 0 时结果与该项颜色完全相同。 */
internal fun blendColors(colors: List<Color>, weights: List<Float>): Color {
    require(colors.size == weights.size) { "colors 与 weights 数量不一致" }
    var red = 0f
    var green = 0f
    var blue = 0f
    var alpha = 0f
    var total = 0f
    colors.forEachIndexed { index, color ->
        val weight = weights[index].coerceIn(0f, 1f)
        if (weight == 0f) return@forEachIndexed
        val srgb = color.convert(ColorSpaces.Srgb)
        red += srgb.red * weight
        green += srgb.green * weight
        blue += srgb.blue * weight
        alpha += srgb.alpha * weight
        total += weight
    }
    if (total == 0f) return colors[weights.indices.maxBy { weights[it] }]
    return Color(red / total, green / total, blue / total, alpha / total)
}

/** 开/关两态的颜色过渡比例（0 关、1 开），与 [blendColors] 配合使用，见 [animateStateWeights]。 */
@Composable
internal fun animateToggleWeights(on: Boolean, label: String): List<Float> =
    animateStateWeights(on, ToggleStates, label)

/** [animateToggleWeights] 的颜色两端：关在前、开在后。 */
internal fun toggleColor(off: Color, on: Color, weights: List<Float>): Color = blendColors(listOf(off, on), weights)

private val ToggleStates = listOf(false, true)
private val HeroTones = HeroTone.entries

/** 头卡当前色调的配色：色调变化时过渡，主题变化时立即到位（见 [animateStateWeights]）。 */
@Composable
internal fun animatedHeroPalette(tone: HeroTone): TonalPalette {
    val weights = animateStateWeights(tone, HeroTones, label = "heroTone")
    val palettes = HeroTones.map { heroPalette(it) }
    return TonalPalette(
        container = blendColors(palettes.map { it.container }, weights),
        content = blendColors(palettes.map { it.content }, weights),
        secondary = blendColors(palettes.map { it.secondary }, weights),
        decoration = blendColors(palettes.map { it.decoration }, weights),
        glyphInk = blendColors(palettes.map { it.glyphInk }, weights),
    )
}

/** 磁贴的底色、文字色与状态文字色。 */
internal class TileColors(val container: Color, val content: Color, val status: Color)

/** 磁贴开启/关闭时的配色：开关变化时过渡，主题变化时立即到位（见 [animateStateWeights]）。 */
@Composable
internal fun animatedTileColors(active: Boolean): TileColors {
    val colors = MiuixTheme.colorScheme
    val activePalette = heroPalette(HeroTone.Accent)
    val weights = animateToggleWeights(active, label = "tileActive")
    return TileColors(
        container = toggleColor(colors.surfaceContainer, activePalette.container, weights),
        content = toggleColor(colors.onSurface, activePalette.content, weights),
        status = toggleColor(colors.onSurfaceVariantSummary, activePalette.secondary, weights),
    )
}

/**
 * 页面顶部的大头卡：标题、可选的状态说明、左下角可选的附加提示（纯文字，逐条排列，贴卡片底部），右下角一枚放大的装饰图标。
 * 配色与图标随 [tone]、[decorationIcon] 变化时平滑过渡。
 */
@Composable
fun HeroCard(
    title: String,
    overline: String? = null,
    subtitle: String? = null,
    decorationIcon: ImageVector,
    modifier: Modifier = Modifier,
    tone: HeroTone = HeroTone.Accent,
    notes: List<String> = emptyList(),
    onClick: (() -> Unit)? = null,
    /** 卡片最小高度；右下角装饰图标按同一比例随之缩放（默认即 Apple Music 体验优化页的头卡）。 */
    minHeight: Dp = HeroMinHeight,
    /** 装饰图标再按此比例缩小：矮卡片上图标若按高度原样缩放，会顶到右上角的箭头。 */
    decorationScale: Float = 1f,
) {
    val palette = animatedHeroPalette(tone)
    val container = palette.container
    val content = palette.content
    val secondary = palette.secondary
    val decoration = palette.decoration
    val decorationSize = minHeight * decorationScale * HeroDecorationScale
    val decorationOffsetX = minHeight * decorationScale * HeroDecorationOffsetX
    val iconLeftFraction = remember(decorationIcon) { decorationIcon.visibleLeftFraction() }
    // 和图标同一高度的文字行在右边多让出这么多，换行落在图标左边，不压到图标上。
    val avoidIcon = Modifier.padding(
        end = heroTextEndInset(decorationSize, decorationOffsetX, iconLeftFraction) - HeroTextHorizontalPadding,
    )
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = container, contentColor = content),
        pressFeedbackType = if (onClick != null) CardPressFeedback else PressFeedbackType.None,
        showIndication = onClick != null,
        onClick = onClick,
    ) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = minHeight)) {
            Crossfade(
                targetState = decorationIcon,
                animationSpec = stateChangeTween(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(
                        x = decorationOffsetX,
                        y = minHeight * decorationScale * HeroDecorationOffsetY,
                    )
                    .size(decorationSize),
                label = "heroDecorationIcon",
            ) { icon ->
                Icon(icon, contentDescription = null, tint = decoration, modifier = Modifier.fillMaxSize())
            }
            Column(
                modifier = Modifier
                    .heightIn(min = minHeight)
                    .padding(horizontal = HeroTextHorizontalPadding, vertical = 16.dp),
            ) {
                // 可点击时箭头放在卡片右上角，跟第一行文字（有 overline 时是 overline，否则是标题）同排居中。
                if (overline != null) {
                    HeroFirstLine(showArrow = onClick != null, arrowTint = content) { textModifier ->
                        Text(
                            text = overline,
                            fontSize = 13.sp,
                            color = secondary,
                            modifier = textModifier,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
                HeroFirstLine(showArrow = onClick != null && overline == null, arrowTint = content) { textModifier ->
                    Text(
                        text = title,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = content,
                        // 有小字时大标题在第二行，和图标同高，也要让开；没有小字时它是带箭头的第一行，不动。
                        modifier = if (overline != null) textModifier.then(avoidIcon) else textModifier,
                    )
                }
                if (subtitle != null) {
                    Spacer(Modifier.height(4.dp))
                    HeroBodyText(
                        text = subtitle,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        color = secondary,
                        modifier = avoidIcon,
                    )
                }
                if (notes.isNotEmpty()) {
                    // 附加提示贴卡片左下角：卡片高于内容时，中间的空白留在提示上方。
                    Spacer(Modifier.height(10.dp))
                    Spacer(Modifier.weight(1f))
                }
                notes.forEachIndexed { index, note ->
                    if (index > 0) Spacer(Modifier.height(4.dp))
                    HeroBodyText(
                        text = note,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = content,
                        modifier = avoidIcon,
                    )
                }
            }
        }
    }
}

/** 头卡的一行文字，[showArrow] 时在行尾放一枚前进箭头，文字占满其余宽度。 */
@Composable
private fun HeroFirstLine(
    showArrow: Boolean,
    arrowTint: Color,
    text: @Composable (Modifier) -> Unit,
) {
    if (!showArrow) {
        text(Modifier)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        text(Modifier.weight(1f))
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = null,
            tint = arrowTint,
            modifier = Modifier
                .padding(start = 8.dp)
                // 让出空白要放在定尺寸之前：放在之后会被 18dp 的固定约束撑回原宽并居中，只挪一半。
                .trimTrailingSpace(HeroArrowSize * HeroArrowTrailingSpaceFraction)
                .size(HeroArrowSize),
        )
    }
}

private val HeroArrowSize = 18.dp

/**
 * ChevronForward 字形在自己的视口里左右各留约 27% 的空白（Regular 档字形横跨 325.6–881.6 / 1207.2），
 * 按 18dp 画时右边约 4.85dp 是空的，字形右缘就落在离卡片右边约 22.85dp 处，比左边从 18dp 起的文字往里缩了一截。
 * 这里按矢量路径的真实右缘算出这段空白占图标宽度的比例，头卡布局时把它让出去，字形右缘正好落在内边距线上。
 */
internal val HeroArrowTrailingSpaceFraction: Float by lazy { MiuixIcons.ChevronForward.trailingSpaceFraction() }

/** 矢量图标里所有路径的最右边到视口右边的空白，占视口宽度的比例（只看路径本身，不含分组的横向变换）。 */
internal fun ImageVector.trailingSpaceFraction(): Float {
    var right = 0f
    fun visit(group: VectorGroup) {
        for (node in group) when (node) {
            is VectorPath -> right = maxOf(right, PathParser().addPathNodes(node.pathData).toPath().getBounds().right)
            is VectorGroup -> visit(node)
        }
    }
    visit(root)
    return ((viewportWidth - right) / viewportWidth).coerceIn(0f, 1f)
}

/**
 * 矢量图标真正画到的左缘，占视口宽度的比例：所有路径外接框的最左边，描边路径再减去半个线宽。
 * 外接框含曲线控制点，算出来只会偏左（让得更多），不会让文字压上去；带变换的分组无法直接算，保守地按 0 算。
 */
internal fun ImageVector.visibleLeftFraction(): Float {
    var left = Float.MAX_VALUE
    fun visit(group: VectorGroup) {
        for (node in group) when (node) {
            is VectorPath -> {
                val bounds = PathParser().addPathNodes(node.pathData).toPath().getBounds()
                if (!bounds.isEmpty) {
                    val half = if (node.stroke != null) node.strokeLineWidth / 2f else 0f
                    left = minOf(left, bounds.left - half)
                }
            }
            is VectorGroup -> {
                val transformed = node.rotation != 0f || node.scaleX != 1f || node.scaleY != 1f ||
                    node.translationX != 0f || node.translationY != 0f
                if (transformed) left = 0f else visit(node)
            }
        }
    }
    visit(root)
    if (left == Float.MAX_VALUE) return 0f
    return (left / viewportWidth).coerceIn(0f, 1f)
}

/**
 * 头卡里和右下角装饰图标同一高度的文字（第二行起的标题、副标题、附加提示）右侧要让出的宽度，从卡片右边算起：
 * 图标格子左缘到卡片右边是「格子边长 − 向右偏出量」，再往里退图标自身左边的留白，就是图标真正画到的左缘；
 * 文字右缘再离它 [HeroTextIconGap]。不小于卡片原本的右内边距。
 */
internal fun heroTextEndInset(decorationSize: Dp, offsetX: Dp, visibleLeftFraction: Float): Dp =
    maxOf(HeroTextHorizontalPadding, decorationSize - offsetX - decorationSize * visibleLeftFraction + HeroTextIconGap)

/**
 * 头卡的说明文字（副标题、附加提示）：中文按标点断行，不把一个词拆到两行。
 * 先用同一套字号量好每一行，把换行符插进文字里再交给 [Text]，所以 [Text] 自己不会再折行。
 */
@Composable
private fun HeroBodyText(
    text: String,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    // 与 miuix Text 的默认样式合并方式一致，量出来的宽度才和最终排版一样。
    val style = MiuixTheme.textStyles.main.merge(fontSize = fontSize, lineHeight = lineHeight, fontWeight = fontWeight)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxWidth = constraints.maxWidth
        val broken = remember(text, style, maxWidth) {
            if (maxWidth == Constraints.Infinity) {
                text
            } else {
                heroLineBreaks(text, maxWidth.toFloat()) { line ->
                    measurer.measure(line, style, softWrap = false, maxLines = 1).size.width.toFloat()
                }
            }
        }
        Text(text = broken, style = style, color = color)
    }
}

/** 可以在其后断行的标点：中文全角标点在任何位置都可以；英文标点只有后面跟空格时才算（「8.5.0」里的点不算）。 */
private const val HeroCjkBreakPunctuation = "，。、；：！？）」』…"
private const val HeroLatinBreakPunctuation = ",.;:!?)"

/** 不能放在行首的字符：跟着前一个字走。 */
private const val HeroNoLineStart = "，。、；：！？）」』…·,.;:!?)%"

private fun isHeroCjk(c: Char) = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' || c in '\u3000'..'\u303F' || c in '\uFF00'..'\uFFEF'

/** 当前行占到这么宽就直接在标点或中英文空格处换行；不到时先试着在中英文空格处把这一行补满一些。 */
private const val HeroMinLineFill = 0.55f

/** 当前行短于这个宽度（一两个字）时，宁可按词补满，也不留一行孤字。 */
private const val HeroTinyLineFill = 0.2f

/**
 * 给头卡说明文字定断行，返回插好换行符的文字。断点的优先级：
 * 1. 标点后面（「，」「。」等）；
 * 2. 中英文之间的空格（「选择 Lyricon」中间）；英文词之间的空格不算，「Apple Music」不拆；
 * 3. 前两种都放不下时才在词与词之间断（ICU 中文分词），英文单词、数字版本号仍是整体。
 * 当前行不到 [HeroMinLineFill] 宽时，先试着在中英文空格处补字；补不进去仍在标点处换行，
 * 只有当前行短于 [HeroTinyLineFill] 时才按词补。在词间断出来的最后一行只剩一两个字时，从上一行挪一个词下来。
 * 不含汉字的文字（英文界面）原样返回，交给系统按词断行。
 */
internal fun heroLineBreaks(text: String, maxWidth: Float, measure: (String) -> Float): String {
    if (text.none(::isHeroCjk) || '\n' in text || maxWidth <= 0f) return text
    val builder = HeroLineBuilder(maxWidth, measure)
    heroSplit(text, 0).forEach { builder.place(it, 0) }
    return builder.finish()
}

/** 把文字按某一级断点切开，每一块都带着它后面的空格。第 2 级按词切。 */
private fun heroSplit(text: String, level: Int): List<String> {
    if (level >= 2) return heroWordUnits(text)
    val pieces = mutableListOf<String>()
    val current = StringBuilder()
    fun flush() {
        if (current.isNotEmpty()) pieces += current.toString()
        current.clear()
    }
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val next = text.getOrNull(i + 1)
        current.append(c)
        val breakAfter = when (level) {
            0 -> c in HeroCjkBreakPunctuation || (c in HeroLatinBreakPunctuation && (next == null || next == ' '))
            else -> c == ' ' && (isHeroCjk(text.getOrNull(i - 1) ?: ' ') || isHeroCjk(next ?: ' '))
        }
        // 断点后面紧跟的空格并进前一块，下一行不以空格开头。
        if (breakAfter) {
            while (i + 1 < text.length && text[i + 1] == ' ') {
                current.append(' ')
                i++
            }
            flush()
        }
        i++
    }
    flush()
    return pieces
}

/**
 * 按词切开（系统自带的 ICU 中文分词），前两级都放不下时只在词与词之间断：
 * 标点和空格跟着前一个词；英文、数字连着的部分（「8.5.0-canary-250092」）合成一块，不拆。
 */
private fun heroWordUnits(text: String): List<String> {
    val iterator = BreakIterator.getWordInstance(Locale.SIMPLIFIED_CHINESE)
    iterator.setText(text)
    val units = mutableListOf<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        val word = text.substring(start, end)
        val previous = units.lastOrNull()
        val attach = previous != null && (
            word.isBlank() ||
                word.all { it in HeroNoLineStart } ||
                (!previous.last().isWhitespace() && !isHeroCjk(previous.last()) && !isHeroCjk(word.first()))
            )
        if (attach) units[units.lastIndex] = previous + word else units += word
        start = end
        end = iterator.next()
    }
    return units
}

private class HeroLineBuilder(private val maxWidth: Float, private val measure: (String) -> Float) {
    private val lines = mutableListOf<String>()
    /** 每一行（从第二行起）开头那次换行是哪一级断点：0 标点、1 中英文空格、2 词与词之间。 */
    private val breakLevels = mutableListOf<Int>()
    private var current = StringBuilder()

    private fun width(s: String) = measure(s.trimEnd())
    private fun fits(s: String) = width(s) <= maxWidth

    private fun newLine(level: Int, start: String) {
        lines += current.toString().trimEnd()
        breakLevels += level
        current = StringBuilder(start.trimStart())
    }

    fun place(piece: String, level: Int) {
        if (fits(current.toString() + piece)) {
            current.append(piece)
            return
        }
        if (current.isBlank()) {
            // 一整段在空行里都放不下：拆成下一级再放；一个词也放不下（超长英文单词）就整个放进去，交给系统折行。
            if (level < 2) heroSplit(piece, level + 1).forEach { place(it, level + 1) } else current.append(piece)
            return
        }
        val fill = width(current.toString()) / maxWidth
        if (level < 2 && fits(piece.trimStart())) {
            // 这一段在新的一行放得下：当前行够满就在这里换行。
            if (fill >= HeroMinLineFill) {
                newLine(level, piece)
                return
            }
            // 当前行偏短：先看这一段开头能不能在中英文空格处干净地补进当前行。
            if (level == 0) {
                val chunks = heroSplit(piece, 1)
                if (chunks.size > 1 && fits(current.toString() + chunks.first())) {
                    chunks.forEach { place(it, 1) }
                    return
                }
            }
            // 补不进去也宁可在标点处换行；只有当前行短到一两个字时才按词补满。
            if (fill >= HeroTinyLineFill) {
                newLine(level, piece)
                return
            }
        }
        if (level < 2) {
            heroSplit(piece, level + 1).forEach { place(it, level + 1) }
            return
        }
        newLine(2, piece)
    }

    fun finish(): String {
        // 在词间断出来的最后一行只剩一两个字：从上一行挪一个词下来，直到最后一行超过两个字或上一行不能再挪。
        repeat(2) {
            val last = current.toString().trim()
            if (lines.isEmpty() || breakLevels.last() != 2 || last.count { !it.isWhitespace() } > 2) return@repeat
            val previous = lines.last()
            val units = heroSplit(previous, 2)
            if (units.size < 2) return@repeat
            val moved = units.last()
            val candidate = moved + current.toString()
            if (!fits(candidate)) return@repeat
            lines[lines.lastIndex] = units.dropLast(1).joinToString("").trimEnd()
            current = StringBuilder(candidate)
        }
        return (lines + current.toString().trimEnd()).joinToString("\n")
    }
}

/** 头卡文字左右内边距。 */
private val HeroTextHorizontalPadding = 18.dp

/** 文字右缘与装饰图标可见左缘之间留的空。 */
internal val HeroTextIconGap = 10.dp

/** 布局宽度少报 [space]：内容照原尺寸画，右侧这段空白伸进外面的内边距里，不占行宽。 */
private fun Modifier.trimTrailingSpace(space: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val trim = space.roundToPx().coerceIn(0, placeable.width)
    layout(placeable.width - trim, placeable.height) { placeable.place(0, 0) }
}

/** 一枚磁贴：图标在上，标题与状态在下；[active] 时换成主题色的浅色底（与主页头卡同一套配色）。 */
class SettingsTileSpec(
    val key: String,
    val title: String,
    val status: String,
    val active: Boolean,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
    val icon: @Composable (tint: Color, modifier: Modifier) -> Unit,
)

/** 磁贴宫格：3 枚时排成一行三列，其余按两列排，单数时最后一枚占满整行。 */
@Composable
fun SettingsTileGrid(tiles: List<SettingsTileSpec>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(horizontal = CardHorizontalMargin)
            .padding(bottom = CardBottomMargin)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TileGap),
    ) {
        val columns = if (tiles.size == 3) 3 else 2
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                // 不足一行的末行不补空位，磁贴按权重分满整行。
                row.forEach { tile -> key(tile.key) { SettingsTile(tile, Modifier.weight(1f)) } }
            }
        }
    }
}

/** 单枚磁贴；需要自己排版（如磁贴上要挂弹出菜单）时直接用。 */
@Composable
internal fun SettingsTile(tile: SettingsTileSpec, modifier: Modifier) {
    val tileColors = animatedTileColors(tile.active)
    val container = tileColors.container
    val content = tileColors.content
    val status = tileColors.status
    Card(
        modifier = modifier
            .height(TileHeight)
            .alpha(if (tile.enabled) 1f else 0.45f),
        colors = CardDefaults.defaultColors(color = container, contentColor = content),
        pressFeedbackType = if (tile.enabled) CardPressFeedback else PressFeedbackType.None,
        showIndication = tile.enabled,
        onClick = if (tile.enabled) tile.onClick else null,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(TileInsideMargin)) {
            tile.icon(content, Modifier.size(TileIconSize))
            Spacer(Modifier.weight(1f))
            Text(
                text = tile.title,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
                color = content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = tile.status,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = status,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

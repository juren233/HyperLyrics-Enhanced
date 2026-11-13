/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
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
            // 三角形顶点 (12,3)、(22,20.5)、(2,20.5)，三个角各以 2 个单位圆角过渡。
            moveTo(12.99f, 4.74f)
            lineTo(21.01f, 18.76f)
            quadTo(22f, 20.5f, 20f, 20.5f)
            lineTo(4f, 20.5f)
            quadTo(2f, 20.5f, 2.99f, 18.76f)
            lineTo(11.01f, 4.74f)
            quadTo(12f, 3f, 12.99f, 4.74f)
            close()
            moveTo(12f, 9.5f)
            lineTo(12f, 13.8f)
        }, dotY = 17f)
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

/** 信息提示卡片：浅色底 + 图标 + 文字。[emphasized] 时文字加大加粗，与 [WarningNoticeCard] 同字号。 */
@Composable
fun NoticeCard(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val colors = MiuixTheme.colorScheme
    NoticeCardLayout(text, icon, colors.tertiaryContainer, colors.onTertiaryContainer, colors.onSurface, emphasized, modifier)
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
private val TileColorAnimation = stateChangeTween<Color>()
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

private val HeroSuccessHue = Color(0xFF2DB55D)
private val HeroWarningHue = Color(0xFFF2A516)
private val HeroErrorHue = Color(0xFFE5484D)

private class TonalPalette(
    val container: Color,
    val content: Color,
    val secondary: Color,
    val decoration: Color,
)

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
    )
}

@Composable
private fun heroPalette(tone: HeroTone): TonalPalette = tonalPalette(
    when (tone) {
        HeroTone.Accent -> MiuixTheme.colorScheme.primary
        HeroTone.Success -> HeroSuccessHue
        HeroTone.Warning -> HeroWarningHue
        HeroTone.Error -> HeroErrorHue
    }
)

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
) {
    val palette = heroPalette(tone)
    val container by animateColorAsState(palette.container, TileColorAnimation, label = "heroContainer")
    val content by animateColorAsState(palette.content, TileColorAnimation, label = "heroContent")
    val secondary by animateColorAsState(palette.secondary, TileColorAnimation, label = "heroSecondary")
    val decoration by animateColorAsState(palette.decoration, TileColorAnimation, label = "heroDecoration")
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = container, contentColor = content),
        pressFeedbackType = if (onClick != null) CardPressFeedback else PressFeedbackType.None,
        showIndication = onClick != null,
        onClick = onClick,
    ) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = HeroMinHeight)) {
            Crossfade(
                targetState = decorationIcon,
                animationSpec = stateChangeTween(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 22.dp, y = 26.dp)
                    .size(124.dp),
                label = "heroDecorationIcon",
            ) { icon ->
                Icon(icon, contentDescription = null, tint = decoration, modifier = Modifier.fillMaxSize())
            }
            Column(
                modifier = Modifier
                    .heightIn(min = HeroMinHeight)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
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
                        modifier = textModifier,
                    )
                }
                if (subtitle != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        color = secondary,
                    )
                }
                if (notes.isNotEmpty()) {
                    // 附加提示贴卡片左下角：卡片高于内容时，中间的空白留在提示上方。
                    Spacer(Modifier.height(10.dp))
                    Spacer(Modifier.weight(1f))
                }
                notes.forEachIndexed { index, note ->
                    if (index > 0) Spacer(Modifier.height(4.dp))
                    Text(
                        text = note,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = content,
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
                .size(18.dp),
        )
    }
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

@Composable
private fun SettingsTile(tile: SettingsTileSpec, modifier: Modifier) {
    val colors = MiuixTheme.colorScheme
    val activePalette = heroPalette(HeroTone.Accent)
    val container by animateColorAsState(
        targetValue = if (tile.active) activePalette.container else colors.surfaceContainer,
        animationSpec = TileColorAnimation,
        label = "tileContainer",
    )
    val content by animateColorAsState(
        targetValue = if (tile.active) activePalette.content else colors.onSurface,
        animationSpec = TileColorAnimation,
        label = "tileContent",
    )
    val status by animateColorAsState(
        targetValue = if (tile.active) activePalette.secondary else colors.onSurfaceVariantSummary,
        animationSpec = TileColorAnimation,
        label = "tileStatus",
    )
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

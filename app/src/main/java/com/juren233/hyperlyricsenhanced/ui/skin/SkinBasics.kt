/*
 * Copyright 2026 juren233
 */
package com.juren233.hyperlyricsenhanced.ui.skin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text as MaterialText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioSlider
import io.github.neoruaa.glasmio.ui.GlasmioSwitch
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import kotlin.math.roundToInt

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = TextStyle.Default,
) {
    if (LocalUiStyle.current.isGlasmio) {
        MaterialText(
            text = text,
            modifier = modifier,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = textAlign,
            lineHeight = lineHeight,
            overflow = overflow,
            maxLines = maxLines,
            style = style,
        )
    } else {
        MiuixText(
            text = text,
            modifier = modifier,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = textAlign,
            lineHeight = lineHeight,
            overflow = overflow,
            maxLines = maxLines,
            style = style,
        )
    }
}

@Composable
fun Icon(
    icon: SkinIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    if (LocalUiStyle.current.isGlasmio) {
        if (tint == Color.Unspecified) {
            SfIcon(systemName = icon.sfSymbol, contentDescription = contentDescription, modifier = modifier)
        } else {
            SfIcon(systemName = icon.sfSymbol, contentDescription = contentDescription, modifier = modifier, tint = tint)
        }
    } else if (tint == Color.Unspecified) {
        MiuixIcon(imageVector = icon.miuix, contentDescription = contentDescription, modifier = modifier)
    } else {
        MiuixIcon(imageVector = icon.miuix, contentDescription = contentDescription, modifier = modifier, tint = tint)
    }
}

/** 位图/矢量资源图标（非语义图标）：Glasmio 风格下同样取玻璃内容色，不借用 Miuix 色。 */
@Composable
fun Icon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    if (LocalUiStyle.current.isGlasmio) {
        androidx.compose.material3.Icon(
            painter = painter,
            contentDescription = contentDescription,
            modifier = modifier,
            tint = if (tint == Color.Unspecified) GlasmioTheme.colors.content else tint,
        )
    } else if (tint == Color.Unspecified) {
        MiuixIcon(painter = painter, contentDescription = contentDescription, modifier = modifier)
    } else {
        MiuixIcon(painter = painter, contentDescription = contentDescription, modifier = modifier, tint = tint)
    }
}

/** 品牌/插画类矢量图（非语义图标）：Glasmio 风格下同样取玻璃内容色作默认着色。 */
@Composable
fun Icon(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    if (LocalUiStyle.current.isGlasmio) {
        androidx.compose.material3.Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = modifier,
            tint = if (tint == Color.Unspecified) GlasmioTheme.colors.content else tint,
        )
    } else if (tint == Color.Unspecified) {
        MiuixIcon(imageVector = imageVector, contentDescription = contentDescription, modifier = modifier)
    } else {
        MiuixIcon(imageVector = imageVector, contentDescription = contentDescription, modifier = modifier, tint = tint)
    }
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    holdDownState: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (LocalUiStyle.current.isGlasmio) {
        GlasmioIconButton(onClick = onClick, modifier = modifier) { content() }
    } else {
        MiuixIconButton(onClick = onClick, modifier = modifier, holdDownState = holdDownState, content = content)
    }
}

@Composable
fun TextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    if (LocalUiStyle.current.isGlasmio) {
        GlasmioButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            emphasis = if (primary) GlassEmphasis.Prominent else GlassEmphasis.Regular,
        ) { MaterialText(text) }
    } else {
        MiuixTextButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
        )
    }
}

@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (LocalUiStyle.current.isGlasmio) {
        GlasmioSwitch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
    } else {
        MiuixSwitch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
    }
}

/** [steps] 为中间刻度数（与 Miuix 含义相同）；Glasmio 滑块无刻度，这里按刻度吸附取值。 */
@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    keyPoints: List<Float>? = null,
    stepHaptics: Boolean = false,
) {
    if (LocalUiStyle.current.isGlasmio) {
        val snap: (Float) -> Float = { raw ->
            if (steps <= 0) raw else {
                val span = valueRange.endInclusive - valueRange.start
                val unit = span / (steps + 1)
                valueRange.start + ((raw - valueRange.start) / unit).roundToInt() * unit
            }
        }
        GlasmioSlider(
            value = value,
            onValueChange = { onValueChange(snap(it)) },
            modifier = modifier,
            valueRange = valueRange,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
        )
    } else {
        MiuixSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            showKeyPoints = keyPoints != null,
            keyPoints = keyPoints,
            hapticEffect = if (stepHaptics) SliderDefaults.SliderHapticEffect.Step else SliderDefaults.SliderHapticEffect.None,
        )
    }
}

@Composable
fun HorizontalDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(0.5.dp).background(SkinTheme.colors.dividerLine))
}

@Composable
fun VerticalDivider(modifier: Modifier = Modifier, color: Color = SkinTheme.colors.dividerLine, thickness: Dp = 0.5.dp) {
    Box(modifier.width(thickness).fillMaxHeight().background(color))
}

/** 勾选框。Miuix = Miuix Checkbox；Glasmio 没有复选框组件，用 SF Symbols 的圆形对勾（iOS 勾选列表的惯例）。 */
@Composable
fun Checkbox(checked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (LocalUiStyle.current.isGlasmio) {
        Box(modifier.clickable(onClick = onClick).padding(4.dp)) {
            SfIcon(
                systemName = if (checked) "checkmark.circle.fill" else "circle",
                contentDescription = null,
                tint = if (checked) GlasmioTheme.colors.accent else GlasmioTheme.colors.secondaryContent,
            )
        }
    } else {
        top.yukonga.miuix.kmp.basic.Checkbox(
            state = androidx.compose.ui.state.ToggleableState(checked),
            onClick = onClick,
            modifier = modifier,
        )
    }
}

/** 加载指示。Miuix = Miuix 进度圈；Glasmio = Material3 进度圈（Glasmio 无独立组件），取其强调色。 */
@Composable
fun CircularProgressIndicator(modifier: Modifier = Modifier) {
    if (LocalUiStyle.current.isGlasmio) {
        androidx.compose.material3.CircularProgressIndicator(modifier = modifier, color = GlasmioTheme.colors.accent)
    } else {
        top.yukonga.miuix.kmp.basic.CircularProgressIndicator(modifier = modifier)
    }
}

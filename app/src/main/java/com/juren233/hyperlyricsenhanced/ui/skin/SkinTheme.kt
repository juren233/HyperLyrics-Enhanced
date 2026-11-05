/*
 * Copyright 2026 juren233
 */
package com.juren233.hyperlyricsenhanced.ui.skin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.IosTypography
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 页面使用的语义颜色。Miuix 风格取 [MiuixTheme]，Glasmio 风格取 [GlasmioTheme]，
 * 两套映射都只在本文件定义，页面不再直接读取任一套主题。
 */
class SkinColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val surface: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val onSurface: Color,
    val onBackground: Color,
    val onSurfaceSecondary: Color,
    val onSurfaceVariantSummary: Color,
    val onSurfaceVariantActions: Color,
    val onSurfaceContainer: Color,
    val onSurfaceContainerHigh: Color,
    val disabledOnSecondaryVariant: Color,
    val dividerLine: Color,
    val outline: Color,
)

class SkinTextStyles(
    val body2: TextStyle,
    val headline1: TextStyle,
    val title2: TextStyle,
    val title3: TextStyle,
    val button: TextStyle,
)

object SkinTheme {
    val colors: SkinColors
        @Composable @ReadOnlyComposable get() = if (LocalUiStyle.current.isGlasmio) {
            val g = GlasmioTheme.colors
            SkinColors(
                primary = g.accent,
                onPrimary = Color.White,
                primaryContainer = g.accent.copy(alpha = 0.15f),
                surface = g.groupedBackground,
                surfaceContainer = g.elevatedBackground,
                surfaceContainerHigh = g.elevatedBackground,
                onSurface = g.content,
                onBackground = g.content,
                onSurfaceSecondary = g.secondaryContent,
                onSurfaceVariantSummary = g.secondaryContent,
                onSurfaceVariantActions = g.secondaryContent,
                onSurfaceContainer = g.content,
                onSurfaceContainerHigh = g.content,
                disabledOnSecondaryVariant = g.tertiaryContent,
                dividerLine = g.separator,
                outline = g.subtleStroke,
            )
        } else {
            val m = MiuixTheme.colorScheme
            SkinColors(
                primary = m.primary,
                onPrimary = m.onPrimary,
                primaryContainer = m.primaryContainer,
                surface = m.surface,
                surfaceContainer = m.surfaceContainer,
                surfaceContainerHigh = m.surfaceContainerHigh,
                onSurface = m.onSurface,
                onBackground = m.onBackground,
                onSurfaceSecondary = m.onSurfaceSecondary,
                onSurfaceVariantSummary = m.onSurfaceVariantSummary,
                onSurfaceVariantActions = m.onSurfaceVariantActions,
                onSurfaceContainer = m.onSurfaceContainer,
                onSurfaceContainerHigh = m.onSurfaceContainerHigh,
                disabledOnSecondaryVariant = m.disabledOnSecondaryVariant,
                dividerLine = m.dividerLine,
                outline = m.outline,
            )
        }

    val textStyles: SkinTextStyles
        @Composable @ReadOnlyComposable get() = if (LocalUiStyle.current.isGlasmio) {
            SkinTextStyles(
                body2 = IosTypography.subheadline,
                headline1 = IosTypography.headline,
                title2 = IosTypography.title2,
                title3 = IosTypography.title3,
                button = IosTypography.body,
            )
        } else {
            val t = MiuixTheme.textStyles
            SkinTextStyles(
                body2 = t.body2,
                headline1 = t.headline1,
                title2 = t.title2,
                title3 = t.title3,
                button = t.button,
            )
        }
}

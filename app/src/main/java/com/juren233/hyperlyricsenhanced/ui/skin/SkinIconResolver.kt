/*
 * Copyright 2026 juren233
 */
package com.juren233.hyperlyricsenhanced.ui.skin

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.icons.GlasmioIconResolver

/**
 * Glasmio 的 SF Symbols 字体需要宿主自备（库不含 Apple 字体），本项目没有该字体，
 * `SfIcon` 因此回退到 Material 语义图标。回退表里 `chevron.up.chevron.down`（Pop-up Button 的上下箭头）
 * 只会命中 `chevron.up`，丢掉下半边；这里按 Glasmio 文档的 [GlasmioIconResolver] 扩展点补上完整图形，
 * 其余名称返回 null，仍走库内回退。
 */
object SkinIconResolver : GlasmioIconResolver {
    override fun resolve(systemName: String): ImageVector? = when (systemName) {
        "chevron.up.chevron.down" -> upDownChevrons
        else -> null
    }

    private val upDownChevrons: ImageVector = ImageVector.Builder(
        name = "chevron.up.chevron.down",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(6.5f, 9.5f)
            lineTo(12f, 4f)
            lineTo(17.5f, 9.5f)
            moveTo(6.5f, 14.5f)
            lineTo(12f, 20f)
            lineTo(17.5f, 14.5f)
        }
    }.build()
}

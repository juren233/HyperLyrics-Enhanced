/*
 * Copyright 2026 juren233
 */
package com.juren233.hyperlyricsenhanced.ui.skin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.icons.GlasmioIconProvider
import io.github.neoruaa.glasmio.glass.GlasmioBackdropHost
import io.github.neoruaa.glasmio.glass.LocalBlurBackdrop
import io.github.neoruaa.glasmio.glass.glassBackdropSource
import io.github.neoruaa.glasmio.glass.render.Backdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.glass.trackBackdropPosition

/**
 * 界面风格的根容器：读取 [UiStyle] 并向下提供 [LocalUiStyle]。
 * Glasmio 风格安装 GlasmioTheme 与背景采样宿主（采样层只放背景，玻璃控件在叠加层，见 Glasmio 的 Backdrop hosting 规范）。
 * 两种风格互不相容：Glasmio 风格下页面不得渲染任何 Miuix 组件，也不借用 Miuix 配色。
 */
/**
 * [LocalBlurBackdrop] 的非 static 镜像。240056 日志显示：Card 作用域被单独重组时，Glasmio 的 static 本地值
 * 读不到，而同一作用域内的非 static 本地值（[LocalUiStyle]）读取正常；模态玻璃处据此镜像重新提供。
 */
private val LocalSkinBlurBackdrop = compositionLocalOf<Backdrop?> { null }

/** 在 Glasmio 模态玻璃（对话框、面板）的调用处重新提供 [LocalBlurBackdrop]。 */
@Composable
fun ProvideSkinBlurBackdrop(content: @Composable () -> Unit) {
    val backdrop = LocalSkinBlurBackdrop.current
    if (backdrop != null) {
        CompositionLocalProvider(LocalBlurBackdrop provides backdrop, content = content)
    } else {
        content()
    }
}

@Composable
fun SkinHost(isDark: Boolean, content: @Composable () -> Unit) {
    val style = rememberUiStyle()
    CompositionLocalProvider(LocalUiStyle provides style) {
        if (style.isGlasmio) {
            GlasmioTheme(isDark = isDark) {
              GlasmioIconProvider(resolver = SkinIconResolver) {
                val colors = GlasmioTheme.colors
                GlasmioBackdropHost(
                    modifier = Modifier.fillMaxSize(),
                    sampledContent = { Box(Modifier.fillMaxSize().background(colors.groupedBackground)) },
                    overlayContent = {
                        // 模态玻璃（对话框、底部面板）采样已渲染的页面：页面内容记录进独立图层并作为 LocalBlurBackdrop 提供，
                        // 与 Glasmio 示例应用的页面宿主写法一致。
                        val pageBackdrop = rememberLayerBackdrop()
                        CompositionLocalProvider(
                            LocalBlurBackdrop provides pageBackdrop,
                            LocalSkinBlurBackdrop provides pageBackdrop,
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .glassBackdropSource(pageBackdrop)
                                    .trackBackdropPosition(pageBackdrop),
                            ) { content() }
                        }
                    },
                )
              }
            }
        } else {
            content()
        }
    }
}

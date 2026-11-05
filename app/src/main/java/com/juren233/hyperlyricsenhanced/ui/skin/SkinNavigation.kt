/*
 * Copyright 2026 juren233
 */
package com.juren233.hyperlyricsenhanced.ui.skin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import io.github.neoruaa.glasmio.glass.trackBackdropPosition
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.glass.glassBackdropSource
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.ui.utils.BlurredBottomBar
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.scaffold.GlasmioScaffold
import io.github.neoruaa.glasmio.ui.GlasmioNavigationBar
import io.github.neoruaa.glasmio.ui.GlasmioTabItem
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Immutable
class SkinNavItem(val label: String, val icon: SkinIcon)

/** 页面根容器：Miuix = Scaffold；Glasmio = GlasmioScaffold + 玻璃提示条。 */
@Composable
fun SkinScaffold(
    modifier: Modifier = Modifier,
    snackbarHostState: SkinSnackbarHostState? = null,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    if (LocalUiStyle.current.isGlasmio) {
        // 与 Glasmio 示例一致：页面内容记录进独立图层，底栏玻璃叠在其外侧并采样该图层，避免自采样。
        val barBackdrop = rememberLayerBackdrop()
        var barHeightPx by remember { mutableIntStateOf(0) }
        val barHeight = with(LocalDensity.current) { barHeightPx.toDp() }
        Box(modifier) {
            Box(Modifier.fillMaxSize().glassBackdropSource(barBackdrop).trackBackdropPosition(barBackdrop)) {
                GlasmioScaffold(
                    content = { inner ->
                        // 底栏悬浮在内容之上，内容只需在底部让出底栏高度。
                        content(
                            PaddingValues(
                                top = inner.calculateTopPadding(),
                                bottom = maxOf(inner.calculateBottomPadding(), barHeight),
                            ),
                        )
                    },
                )
            }
            Box(
                Modifier.align(Alignment.BottomCenter).onSizeChanged { barHeightPx = it.height },
            ) {
                CompositionLocalProvider(LocalGlassBackdrop provides barBackdrop) { bottomBar() }
            }
            snackbarHostState?.let { SkinSnackbarHost(it) }
        }
    } else {
        Scaffold(
            modifier = modifier,
            snackbarHost = { snackbarHostState?.let { SkinSnackbarHost(it) } },
            bottomBar = bottomBar,
            content = content,
        )
    }
}

/**
 * 主页面底部导航。
 * Miuix 风格：贴底导航栏或悬浮导航栏（[floating]）；
 * Glasmio 风格：GlasmioNavigationBar（本身即悬浮玻璃栏，忽略 [floating]）。
 */
@Composable
fun SkinBottomNavigation(
    visible: Boolean,
    items: List<SkinNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    floating: Boolean,
    miuixBackdrop: LayerBackdrop?,
) {
    if (LocalUiStyle.current.isGlasmio) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            val compactProgress = remember { mutableFloatStateOf(0f) }
            val tabs = remember(items) {
                items.mapIndexed { index, item ->
                    GlasmioTabItem(index, item.label, item.icon.toSfSymbol())
                }
            }
            // 外边距与 Glasmio 示例应用的底栏一致：左右 20dp、底部 8dp，并避开系统导航栏。
            Box(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 8.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                GlasmioNavigationBar(
                    items = tabs,
                    selectedKey = selectedIndex,
                    onSelected = onSelect,
                    onExpand = {},
                    compactProgress = compactProgress,
                    compactSize = 48.dp,
                )
            }
        }
        return
    }
    val blurActive = miuixBackdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    AnimatedVisibility(
        visible = visible && !floating,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
    ) {
        BlurredBottomBar(miuixBackdrop, blurActive) {
            Box(modifier = Modifier.background(barColor)) {
                NavigationBar(color = barColor, showDivider = false) {
                    items.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = selectedIndex == index,
                            onClick = { onSelect(index) },
                            icon = item.icon.miuix,
                            label = item.label,
                        )
                    }
                }
            }
        }
    }
    AnimatedVisibility(
        visible = visible && floating,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
    ) {
        val floatingBarColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer
        val floatingBarShape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius)
        val isDark = androidx.compose.foundation.isSystemInDarkTheme()
        val floatingHighlight = remember(isDark) {
            if (isDark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight
        }
        FloatingNavigationBar(
            modifier = (if (blurActive && miuixBackdrop != null) {
                Modifier.textureBlur(
                    backdrop = miuixBackdrop,
                    shape = floatingBarShape,
                    blurRadius = 12f,
                    colors = BlurColors(
                        blendColors = listOf(BlendColorEntry(color = MiuixTheme.colorScheme.surfaceContainer.copy(0.6f))),
                    ),
                    highlight = floatingHighlight,
                )
            } else {
                Modifier
            }).padding(horizontal = 12.dp),
            color = floatingBarColor,
        ) {
            items.forEachIndexed { index, item ->
                FloatingNavigationBarItem(
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) },
                    icon = item.icon.miuix,
                    label = item.label,
                )
                if (index < items.size - 1) Spacer(Modifier.width(12.dp))
            }
        }
    }
}

/** SkinIcon 的 SF Symbols 名称解析为 Glasmio 的符号枚举；未收录时回退到通用符号。 */
private fun SkinIcon.toSfSymbol(): SfSymbol =
    SfSymbol.entries.firstOrNull { it.systemName == sfSymbol } ?: SfSymbol.entries.first()

/**
 * 通用布局容器（顶栏 + 内容 + 底栏三槽，不含任何视觉样式），供自带顶栏/底栏的整页流程（如初始设置向导）使用。
 * Miuix = Miuix Scaffold；Glasmio = Material3 Scaffold（透明容器，与 Glasmio 自身脚手架同源）。
 */
@Composable
fun SkinPlainScaffold(
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    if (LocalUiStyle.current.isGlasmio) {
        androidx.compose.material3.Scaffold(
            modifier = modifier,
            containerColor = Color.Transparent,
            snackbarHost = snackbarHost,
            topBar = topBar,
            bottomBar = bottomBar,
            content = content,
        )
    } else {
        Scaffold(
            modifier = modifier,
            snackbarHost = snackbarHost,
            topBar = topBar,
            bottomBar = bottomBar,
            content = content,
        )
    }
}

package com.juren233.hyperlyricsenhanced.ui.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.common.TopBarProgressiveBlurPreference
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 宽屏最小宽度（dp），达到即按平板布局渲染，与 Material 规范的 medium 断点一致。 */
private const val WIDE_SCREEN_MIN_WIDTH_DP = 600
private val TOP_BAR_BLUR_FADE_HEIGHT = 16.dp

fun Modifier.pageScrollModifiers(
    enableScrollEndHaptic: Boolean,
    showTopAppBar: Boolean,
    topAppBarScrollBehavior: ScrollBehavior,
): Modifier = this
    .then(if (enableScrollEndHaptic) Modifier.scrollEndHaptic() else Modifier)
    .overScrollVertical()
    .then(if (showTopAppBar) Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection) else Modifier)
    .fillMaxHeight()

/**
 * 渐隐带浮层化：让子内容向 [extendUp] 指定的方向多绘制 [extension]（参与模糊渐变范围），
 * 但溢出部分不计入本节点上报的测量高度，Scaffold 据此计算的 innerPadding 不会变大，
 * 页面内容不再被渐隐带推下。Scaffold 的内容先放置、栏后放置，溢出区自然盖在内容之上。
 */
fun Modifier.blurFadeExtension(extension: Dp, extendUp: Boolean): Modifier = layout { measurable, constraints ->
    val extensionPx = extension.roundToPx()
    val placeable = measurable.measure(
        if (constraints.hasBoundedHeight) {
            constraints.copy(maxHeight = constraints.maxHeight + extensionPx)
        } else {
            constraints
        }
    )
    layout(placeable.width, (placeable.height - extensionPx).coerceAtLeast(0)) {
        placeable.placeRelative(0, if (extendUp) -extensionPx else 0)
    }
}

@Composable
fun pageContentPadding(
    innerPadding: PaddingValues,
    outerPadding: PaddingValues,
    isWideScreen: Boolean,
    extraTop: Dp = 0.dp,
    extraStart: Dp = 0.dp,
    extraEnd: Dp = 0.dp,
): PaddingValues {
    val topPadding = innerPadding.calculateTopPadding() + extraTop
    val bottomPadding = if (isWideScreen) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + outerPadding.calculateBottomPadding()
    } else {
        outerPadding.calculateBottomPadding()
    }
    return remember(topPadding, bottomPadding, extraStart, extraEnd) {
        PaddingValues(
            top = topPadding,
            start = extraStart,
            end = extraEnd,
            bottom = bottomPadding,
        )
    }
}

@Composable
fun rememberIsWideScreen(): Boolean {
    val configuration = LocalConfiguration.current
    return remember(configuration) { configuration.screenWidthDp >= WIDE_SCREEN_MIN_WIDTH_DP }
}

@Composable
fun rememberBlurBackdrop(): LayerBackdrop? {
    if (!isRuntimeShaderSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

/** 读取「渐进模糊」开关（含旧版三选项设置迁移），并跟随设置变化实时更新。 */
@Composable
fun rememberProgressiveBlurEnabled(): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
    }
    var progressiveBlurEnabled by remember(prefs) {
        mutableStateOf(TopBarProgressiveBlurPreference.read(prefs))
    }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, key ->
            if (key == UIConstants.KEY_TOP_BAR_PROGRESSIVE_BLUR_MODE ||
                key == UIConstants.KEY_TOP_BAR_PROGRESSIVE_BLUR
            ) {
                progressiveBlurEnabled = TopBarProgressiveBlurPreference.read(changedPrefs)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return progressiveBlurEnabled
}

@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    val progressiveBlurEnabled = rememberProgressiveBlurEnabled()
    val blurColors = BlurColors(
        blendColors = listOf(
            BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f)),
        ),
    )
    val progressiveBlurActive = blurEnabled && backdrop != null && progressiveBlurEnabled
    Box(
        modifier = if (blurEnabled && backdrop != null) {
            if (progressiveBlurActive) {
                Modifier
                    // 渐隐带不计入测量高度：渐变仍按「栏高 + 渐隐带」计算，按钮和标题仍位于原来的坐标，
                    // 页面内容不再被推下；溢出的渐隐带作为浮层盖在内容区顶部。
                    .blurFadeExtension(extension = TOP_BAR_BLUR_FADE_HEIGHT, extendUp = false)
                    .progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        blurRadius = 12f,
                        gradient = ProgressiveBlur.Top.copy(startFraction = 0.5f),
                        colors = blurColors,
                    )
                    .padding(bottom = TOP_BAR_BLUR_FADE_HEIGHT)
            } else {
                Modifier.textureBlur(
                    backdrop = backdrop,
                    shape = RectangleShape,
                    blurRadius = 25f,
                    colors = blurColors,
                )
            }
        } else {
            Modifier
        },
    ) {
        content()
    }
}

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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface
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
/** 顶栏渐进模糊由全模糊开始减弱的位置（占模糊区域高度的比例）。 */
private const val TOP_BAR_BLUR_START_FRACTION = 0.5f
/** 分组头停在顶栏下方时的减弱起点：比平时略靠下（按停住进度从平时的值过渡），清晰端位置不变。 */
private const val PINNED_HEADER_BLUR_START_FRACTION = 0.7f
/** 分组头停在顶栏下方时，头下方的渐隐带高度（按停住进度由 [TOP_BAR_BLUR_FADE_HEIGHT] 过渡）。 */
private val PINNED_HEADER_BLUR_FADE_HEIGHT = 26.dp
private val BOTTOM_BAR_BLUR_FADE_HEIGHT = 16.dp
/** 顶栏整栏模糊（未开渐进模糊）的模糊半径；悬浮块沿用同一值。 */
private const val BAR_BLUR_RADIUS = 25f

/** 页面顶栏取样用的模糊背景，供同一页面的悬浮块共用；没有时为 null。 */
val LocalPageBlurBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

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

/**
 * 顶栏模糊。[pinnedHeight] 是停在顶栏下方的分组头的高度（见 `PinnedHeaderState`），
 * [pinnedProgress] 是它停住的进度（0 到 1）：模糊区域按进度向下延伸盖住头，
 * 相当于顶栏连同头一起变高；清晰端在头下方 [PINNED_HEADER_BLUR_FADE_HEIGHT]，开始减弱的位置按停住进度由
 * [TOP_BAR_BLUR_START_FRACTION] 移到 [PINNED_HEADER_BLUR_START_FRACTION]，中段略往下。
 */
@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurEnabled: Boolean,
    pinnedHeight: Dp = 0.dp,
    pinnedProgress: Float = 0f,
    content: @Composable () -> Unit,
) {
    val progressiveBlurEnabled = rememberProgressiveBlurEnabled()
    val blurColors = barBlurColors()
    val progressiveBlurActive = blurEnabled && backdrop != null && progressiveBlurEnabled
    val progress = pinnedProgress.coerceIn(0f, 1f)
    val pinnedCover = pinnedHeight * progress
    Box(
        modifier = if (blurEnabled && backdrop != null) {
            if (progressiveBlurActive) {
                val fadeHeight = TOP_BAR_BLUR_FADE_HEIGHT + (PINNED_HEADER_BLUR_FADE_HEIGHT - TOP_BAR_BLUR_FADE_HEIGHT) * progress
                val extension = fadeHeight + pinnedCover
                val startFraction = TOP_BAR_BLUR_START_FRACTION +
                    (PINNED_HEADER_BLUR_START_FRACTION - TOP_BAR_BLUR_START_FRACTION) * progress
                Modifier
                    // 渐隐带不计入测量高度：渐变仍按「栏高 + 渐隐带」计算，按钮和标题仍位于原来的坐标，
                    // 页面内容不再被推下；溢出的渐隐带作为浮层盖在内容区顶部。
                    .blurFadeExtension(extension = extension, extendUp = false)
                    .progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        blurRadius = 12f,
                        gradient = ProgressiveBlur.Top.copy(startFraction = startFraction),
                        colors = blurColors,
                    )
                    .padding(bottom = extension)
            } else {
                Modifier
                    .blurFadeExtension(extension = pinnedCover, extendUp = false)
                    .textureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        blurRadius = BAR_BLUR_RADIUS,
                        colors = blurColors,
                    )
                    .padding(bottom = pinnedCover)
            }
        } else {
            Modifier
        },
    ) {
        content()
    }
}

/**
 * 悬浮块的底：与 Card 同样的圆角（squircle），有 [LocalPageBlurBackdrop] 时为与顶栏同半径、同叠色的模糊，
 * 否则为 Card 的默认底色。
 */
@Composable
fun Modifier.floatingBlurSurface(cornerRadius: Dp = CardDefaults.CornerRadius): Modifier {
    val backdrop = LocalPageBlurBackdrop.current
        ?: return squircleSurface(color = MiuixTheme.colorScheme.surfaceContainer, cornerRadius = cornerRadius)
    return squircleClip(cornerRadius).textureBlur(
        backdrop = backdrop,
        shape = RectangleShape,
        blurRadius = BAR_BLUR_RADIUS,
        colors = barBlurColors(),
    )
}

/** 栏模糊叠色的 surface 不透明度：莫奈配色沿用 0.8；系统主题色（非莫奈）下黑白遮罩偏重，按用户反馈减轻（2026-11-13）。 */
private const val BAR_BLUR_SURFACE_ALPHA_MONET = 0.8f
private const val BAR_BLUR_SURFACE_ALPHA_SYSTEM = 0.6f

/** 栏模糊的叠色：顶栏与底栏、渐进模糊与整栏模糊共用。 */
@Composable
private fun barBlurColors(): BlurColors {
    val alpha = if (LocalAppThemeController.current.isMonet) {
        BAR_BLUR_SURFACE_ALPHA_MONET
    } else {
        BAR_BLUR_SURFACE_ALPHA_SYSTEM
    }
    return BlurColors(
        blendColors = listOf(
            BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(alpha)),
        ),
    )
}

/**
 * 底栏模糊：与 [BlurredBar] 对应，开启渐进模糊时从栏底向上渐隐，否则整栏均匀模糊；主页面导航栏与基础设置底栏共用。
 * [content] 的背景由调用方决定（模糊生效时应透明）。
 */
@Composable
fun BlurredBottomBar(
    backdrop: LayerBackdrop?,
    blurEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    val progressiveBlurEnabled = rememberProgressiveBlurEnabled()
    val blurColors = barBlurColors()
    Box(
        modifier = if (blurEnabled && backdrop != null) {
            if (progressiveBlurEnabled) {
                Modifier
                    // 渐隐带不计入测量高度：渐变仍按「栏高 + 渐隐带」计算，栏内控件仍位于原来的坐标，
                    // 页面内容不再被推下；溢出的渐隐带作为浮层盖在内容区底部。
                    .blurFadeExtension(extension = BOTTOM_BAR_BLUR_FADE_HEIGHT, extendUp = true)
                    .progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        blurRadius = 12f,
                        gradient = ProgressiveBlur.Bottom.copy(startFraction = 0.5f),
                        colors = blurColors,
                    )
                    .padding(top = BOTTOM_BAR_BLUR_FADE_HEIGHT)
            } else {
                Modifier.textureBlur(
                    backdrop = backdrop,
                    shape = RectangleShape,
                    blurRadius = 12f,
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

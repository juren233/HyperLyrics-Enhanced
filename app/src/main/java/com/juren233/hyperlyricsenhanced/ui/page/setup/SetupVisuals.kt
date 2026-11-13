/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.setup

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.juren233.hyperlyricsenhanced.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import com.juren233.hyperlyricsenhanced.ui.utils.LocalAppThemeController
import com.juren233.hyperlyricsenhanced.ui.utils.isMonet
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlin.math.hypot

/** 启动图标前景胶囊的视口宽高（ic_launcher_foreground 中 18..494 × 180..332）。 */
internal const val ISLAND_LOGO_WIDTH = 476f
internal const val ISLAND_LOGO_HEIGHT = 152f
internal const val ISLAND_LOGO_ASPECT = ISLAND_LOGO_WIDTH / ISLAND_LOGO_HEIGHT

internal class IslandLogoPalette(
    /** 胶囊本体为纯色，与系统超级岛一致。 */
    val body: Color,
    val detail: Color,
    val accentStart: Color,
    val accentEnd: Color,
    /** 深色背景下胶囊与背景同为深色，用一道细描边勾出轮廓。 */
    val outline: Color,
)

/**
 * 首启动画与完成转场里图标 / 超级岛的配色，跟随应用自己的配色设置：
 * 莫奈取色时取当前莫奈配色（含所选主题色）的深色方案，与界面一致；否则用应用图标的品牌配色。
 */
@Composable
internal fun rememberIslandLogoPalette(): IslandLogoPalette {
    val theme = LocalAppThemeController.current
    val monet = theme.isMonet
    val body: Color
    val detail: Color
    val accentStart: Color
    val accentEnd: Color
    if (monet) {
        // 超级岛胶囊在浅色与深色模式下都是深色，与系统超级岛一致；取深色方案的色调 20 / 80 / 90。
        val darkMonet = remember(theme?.keyColor) {
            ThemeController(colorSchemeMode = ColorSchemeMode.MonetDark, keyColor = theme?.keyColor)
        }.currentColors()
        body = darkMonet.onPrimary
        detail = darkMonet.primary
        accentStart = darkMonet.primary
        accentEnd = darkMonet.onTertiaryContainer
    } else {
        body = colorResource(R.color.logo_body)
        detail = colorResource(R.color.logo_detail)
        accentStart = colorResource(R.color.logo_accent_start)
        accentEnd = colorResource(R.color.logo_accent_end)
    }
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    return remember(body, detail, accentStart, accentEnd, dark) {
        IslandLogoPalette(
            body = body,
            detail = detail,
            accentStart = accentStart,
            accentEnd = accentEnd,
            outline = if (dark) Color.White.copy(alpha = 0.12f) else Color.Transparent,
        )
    }
}

/*
 * 图标骨架（ic_launcher_foreground）各部件的含义：左侧方块是专辑封面，中间两行是歌曲信息
 * （歌名 / 歌手），右侧的大胶囊是歌词。
 */

/** Logo 部件在胶囊 [rect] 内的位置，参数为图标视口坐标（以胶囊左上角为原点）。 */
internal fun islandLogoPart(rect: Rect, x: Float, y: Float, width: Float, height: Float): Rect {
    val u = rect.width / ISLAND_LOGO_WIDTH
    val v = rect.height / ISLAND_LOGO_HEIGHT
    return Rect(Offset(rect.left + x * u, rect.top + y * v), Size(width * u, height * v))
}

internal fun islandLogoCover(rect: Rect): Rect = islandLogoPart(rect, 32f, 24f, 96f, 104f)

internal fun islandLogoCoverRadius(rect: Rect): Float = 36f * rect.width / ISLAND_LOGO_WIDTH

/** 歌名条、歌手条。 */
internal fun islandLogoSongTitle(rect: Rect): Rect = islandLogoPart(rect, 156f, 44f, 112f, 20f)

internal fun islandLogoSongArtist(rect: Rect): Rect = islandLogoPart(rect, 156f, 88f, 74f, 20f)

/** 歌词胶囊。 */
internal fun islandLogoLyric(rect: Rect): Rect = islandLogoPart(rect, 290f, 34f, 160f, 84f)

/**
 * 胶囊本体：纯色圆角胶囊。深色模式下 Logo 带一道细描边勾出轮廓；
 * [rim] 为模拟超级岛的细边框强度（0..1），在 Logo 描边与更亮的岛边框之间过渡。
 */
internal fun DrawScope.drawIslandBody(rect: Rect, palette: IslandLogoPalette, alpha: Float = 1f, rim: Float = 0f) {
    if (rect.width <= 0f || rect.height <= 0f || alpha <= 0f) return
    val radius = minOf(rect.width, rect.height) / 2f
    drawRoundRect(
        color = palette.body,
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = CornerRadius(radius),
        alpha = alpha,
    )
    val outline = lerp(palette.outline, Color.White.copy(alpha = 0.22f), rim.coerceIn(0f, 1f))
    if (outline.alpha > 0f) {
        val stroke = 1.dp.toPx()
        drawRoundRect(
            color = outline,
            topLeft = rect.topLeft + Offset(stroke / 2f, stroke / 2f),
            size = Size(maxOf(0f, rect.width - stroke), maxOf(0f, rect.height - stroke)),
            cornerRadius = CornerRadius(maxOf(0f, radius - stroke / 2f)),
            style = Stroke(stroke),
            alpha = alpha,
        )
    }
}

/** 封面方块：主题双色渐变，可按 [scale] 弹出并带一点回正的旋转。 */
internal fun DrawScope.drawIslandCover(
    rect: Rect,
    cornerRadius: Float,
    palette: IslandLogoPalette,
    scale: Float = 1f,
    alpha: Float = 1f,
) {
    if (scale <= 0f || alpha <= 0f) return
    rotate(degrees = (1f - scale) * -24f, pivot = rect.center) {
        scale(scale = scale, pivot = rect.center) {
            drawRoundRect(
                brush = Brush.linearGradient(
                    listOf(palette.accentStart, palette.accentEnd),
                    start = rect.bottomLeft,
                    end = rect.topRight,
                ),
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(cornerRadius),
                alpha = alpha * scale.coerceIn(0f, 1f),
            )
        }
    }
}

/**
 * 按启动图标的几何绘制 Logo。各部件进度取 0..1，全部为 1 时与图标前景一致：
 * [cover] 与信息条按弹簧值弹出，[lyric] 为歌词胶囊从圆形向两侧伸展的进度，[lyricAlpha] 为其透明度。
 * [bodyAlpha] 单独控制胶囊本体（首启动画中本体先由液态层绘制），[rim] 为模拟岛细边框强度。
 */
internal fun DrawScope.drawIslandLogo(
    topLeft: Offset,
    size: Size,
    palette: IslandLogoPalette,
    cover: Float = 1f,
    songTitle: Float = 1f,
    songArtist: Float = 1f,
    lyric: Float = 1f,
    lyricAlpha: Float = 1f,
    alpha: Float = 1f,
    bodyAlpha: Float = 1f,
    rim: Float = 0f,
) {
    if (size.width <= 0f || size.height <= 0f || alpha <= 0f) return
    val rect = Rect(topLeft, size)
    drawIslandBody(rect, palette, alpha * bodyAlpha, rim)
    drawIslandCover(islandLogoCover(rect), islandLogoCoverRadius(rect), palette, cover, alpha)
    drawIslandSongInfo(rect, palette, songTitle, songArtist, alpha)
    drawIslandLyric(rect, palette, lyric, alpha * lyricAlpha)
}

/** 歌名条与歌手条：以各自中心按 [songTitle] / [songArtist] 弹出。 */
internal fun DrawScope.drawIslandSongInfo(
    rect: Rect,
    palette: IslandLogoPalette,
    songTitle: Float,
    songArtist: Float,
    alpha: Float = 1f,
) {
    val radius = CornerRadius(10f * rect.width / ISLAND_LOGO_WIDTH)
    listOf(islandLogoSongTitle(rect) to songTitle, islandLogoSongArtist(rect) to songArtist).forEach { (bar, progress) ->
        if (progress > 0f && alpha > 0f) {
            scale(scale = progress, pivot = bar.center) {
                drawRoundRect(
                    color = palette.detail,
                    topLeft = bar.topLeft,
                    size = bar.size,
                    cornerRadius = radius,
                    alpha = alpha * progress.coerceIn(0f, 1f),
                )
            }
        }
    }
}

/** 歌词胶囊：按 [progress] 从圆形以中心向两侧伸展成胶囊。 */
internal fun DrawScope.drawIslandLyric(rect: Rect, palette: IslandLogoPalette, progress: Float, alpha: Float = 1f) {
    if (alpha <= 0f) return
    val target = islandLogoLyric(rect)
    val width = target.height + (target.width - target.height) * progress.coerceAtLeast(0f)
    val shape = Rect(Offset(target.center.x - width / 2f, target.top), Size(width, target.height))
    drawRoundRect(
        brush = Brush.horizontalGradient(
            listOf(palette.accentEnd, palette.accentStart),
            startX = target.left,
            endX = target.right,
        ),
        topLeft = shape.topLeft,
        size = shape.size,
        cornerRadius = CornerRadius(target.height / 2f),
        alpha = alpha,
    )
}

/** 静态歌词岛 Logo，宽高比固定为 [ISLAND_LOGO_ASPECT]。 */
@Composable
internal fun IslandLogoMark(modifier: Modifier = Modifier) {
    val palette = rememberIslandLogoPalette()
    Canvas(modifier) {
        drawIslandLogo(Offset.Zero, size, palette)
    }
}

/** 引导步骤指示：当前步骤展开成胶囊，已完成步骤保留浅色主题色。 */
@Composable
internal fun SetupStepIndicator(count: Int, current: Int, modifier: Modifier = Modifier) {
    val primary = MiuixTheme.colorScheme.primary
    val idle = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val width by animateDpAsState(
                targetValue = if (index == current) 22.dp else 6.dp,
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                label = "setup_step_width",
            )
            val color by animateColorAsState(
                targetValue = when {
                    index == current -> primary
                    index < current -> primary.copy(alpha = 0.4f)
                    else -> idle
                },
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                label = "setup_step_color",
            )
            Box(
                Modifier
                    .size(width = width, height = 6.dp)
                    .drawBehind { drawRoundRect(color, cornerRadius = CornerRadius(size.height / 2f)) },
            )
        }
    }
}

/** 选择 / 授权状态标记：空心圆填充为主题色，再画出对勾。 */
@Composable
internal fun SetupCheckMark(checked: Boolean, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "setup_check",
    )
    val primary = MiuixTheme.colorScheme.primary
    val onPrimary = MiuixTheme.colorScheme.onPrimary
    val outline = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    Canvas(modifier.size(22.dp)) {
        val radius = size.minDimension / 2f
        val ring = 1.5.dp.toPx()
        if (progress < 1f) {
            drawCircle(outline, radius = radius - ring / 2f, style = Stroke(ring), alpha = 1f - progress)
        }
        if (progress > 0f) {
            drawCircle(primary, radius = radius * progress)
            drawCheck(onPrimary, ((progress - 0.35f) / 0.65f).coerceIn(0f, 1f), 2.dp.toPx())
        }
    }
}

/** 在 [origin] 起、大小为 [area] 的区域内按进度画出对勾（坐标为区域比例）。 */
internal fun DrawScope.drawCheck(
    color: Color,
    progress: Float,
    strokeWidth: Float,
    origin: Offset = Offset.Zero,
    area: Size = size,
) {
    if (progress <= 0f) return
    val a = origin + Offset(area.width * 0.28f, area.height * 0.52f)
    val b = origin + Offset(area.width * 0.44f, area.height * 0.67f)
    val c = origin + Offset(area.width * 0.73f, area.height * 0.37f)
    val first = hypot(b.x - a.x, b.y - a.y)
    val second = hypot(c.x - b.x, c.y - b.y)
    val drawn = (first + second) * progress
    if (drawn <= first) {
        drawLine(color, a, a + (b - a) * (drawn / first), strokeWidth, StrokeCap.Round)
    } else {
        drawLine(color, a, b, strokeWidth, StrokeCap.Round)
        drawLine(color, b, b + (c - b) * ((drawn - first) / second), strokeWidth, StrokeCap.Round)
    }
}

/** 完成页的对勾：圆形弹出，随后画出对勾。 */
@Composable
internal fun SetupCompletionMark(modifier: Modifier = Modifier) {
    val active = LocalSetupPageActive.current
    val circle = remember { Animatable(0f) }
    val check = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        delay(120)
        launch { circle.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 260f)) }
        delay(260)
        check.animateTo(1f, tween(durationMillis = 360, easing = FastOutSlowInEasing))
    }
    val primary = MiuixTheme.colorScheme.primary
    val onPrimary = MiuixTheme.colorScheme.onPrimary
    Canvas(modifier.size(96.dp)) {
        val base = 38.dp.toPx()
        drawCircle(primary, radius = base * circle.value)
        drawCheck(
            color = onPrimary,
            progress = check.value,
            strokeWidth = 5.dp.toPx(),
            origin = center - Offset(base, base),
            area = Size(base * 2f, base * 2f),
        )
    }
}

/** 当前页是否处于展示中：内容依次浮现的时机。 */
internal val LocalSetupPageActive = compositionLocalOf { true }

/** 当前页的依次浮现是否已播完；播完后再进入视野的列表项直接显示，不再渐显。 */
private val LocalSetupPageSettled = compositionLocalOf { false }

/**
 * 首启动画与完成转场共用的状态栏模拟超级岛。两段动画里的岛必须是同一个岛，
 * 尺寸与位置只在这里定义，不在各自的动画里另写数值。
 */
internal object SetupStatusIsland {
    /** 常驻尺寸：从状态栏长出时。 */
    val RestWidth = 100.dp
    val RestHeight = 26.dp

    /** 音乐态尺寸：显示封面与音频律动时。 */
    val MusicWidth = 118.dp
    val MusicHeight = 32.dp

    /**
     * 岛收回时的尺寸（intro 滴落与 outro 收起共用）：先高度不变、两侧向中间收成圆（[pinch] 0→1），
     * 再整体缩小消失（[vanish] 0→1）。
     */
    fun retract(size: Size, pinch: Float, vanish: Float): Size {
        val width = lerp(size.width, minOf(size.width, size.height), pinch)
        val remain = 1f - vanish
        return Size(width * remain, size.height * remain)
    }

    /** 岛中心的纵坐标（像素）。 */
    fun centerY(statusBarTop: Int, density: Density): Float =
        with(density) { maxOf(statusBarTop / 2f, 19.dp.toPx()) }
}

/** 当前页正在随完成转场退场：内容按浮现的反向依次淡出上移。 */
private val LocalSetupPageExiting = compositionLocalOf { false }

/** 依次浮现的最大序号；退场时序号越大越先离开。 */
private const val SETUP_STAGGER_MAX_ORDER = 5

/** 每一项浮现的时长与上浮距离；完成转场里目标页面的浮现也用这一组。 */
internal const val SETUP_STAGGER_DURATION_MILLIS = 460
internal val SetupStaggerOffset = 22.dp

/** 逐项浮现一项的曲线：引导页内容、完成转场显露的页面及其中逐项组合出的列表项共用。 */
internal val SetupStaggerSpec = tween<Float>(durationMillis = SETUP_STAGGER_DURATION_MILLIS, easing = FastOutSlowInEasing)

/** 逐项浮现中一项在进度 [value] 时的样子：0 时透明并下沉 [SetupStaggerOffset]，1 时就位。 */
internal fun GraphicsLayerScope.setupRevealLayer(value: Float) {
    alpha = value
    translationY = (1f - value) * SetupStaggerOffset.toPx()
}

/** 依次浮现最长的一项（第 5 项）播完所需的时间。 */
private const val SETUP_STAGGER_SETTLE_MILLIS = SETUP_STAGGER_MAX_ORDER * 55L + SETUP_STAGGER_DURATION_MILLIS

/** 退场时每一项相对上一项的延迟与时长。 */
internal const val SETUP_EXIT_STAGGER_MILLIS = 50L
internal const val SETUP_EXIT_DURATION_MILLIS = 220

/** 提供一页的展示状态：页面首次展示时内容依次浮现，此后滚动回来的列表项不再重播。 */
@Composable
internal fun SetupPageScope(active: Boolean, exiting: Boolean = false, content: @Composable () -> Unit) {
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            delay(SETUP_STAGGER_SETTLE_MILLIS)
            settled = true
        }
    }
    CompositionLocalProvider(
        LocalSetupPageActive provides active,
        LocalSetupPageSettled provides settled,
        LocalSetupPageExiting provides exiting,
        content = content,
    )
}

/** 页面内容依次淡入上浮，[order] 决定先后。 */
@Composable
internal fun Modifier.setupStagger(order: Int): Modifier {
    val active = LocalSetupPageActive.current
    val settled = LocalSetupPageSettled.current
    val exiting = LocalSetupPageExiting.current
    val progress = remember { Animatable(if (settled) 1f else 0f) }
    LaunchedEffect(active, exiting) {
        if (exiting) {
            delay((SETUP_STAGGER_MAX_ORDER - order).coerceAtLeast(0) * SETUP_EXIT_STAGGER_MILLIS)
            progress.animateTo(0f, tween(durationMillis = SETUP_EXIT_DURATION_MILLIS, easing = FastOutSlowInEasing))
        } else if (active) {
            delay(order * 55L)
            progress.animateTo(1f, SetupStaggerSpec)
        }
    }
    return graphicsLayer {
        val value = progress.value
        // 浮现时自下而上，退场时继续向上离开。
        if (exiting) {
            alpha = value
            translationY = -(1f - value) * 12.dp.toPx()
        } else {
            setupRevealLayer(value)
        }
    }
}

@Composable
internal fun SetupPageTitle(title: String, summary: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Text(
            text = title,
            fontSize = 28.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface,
        )
        if (summary != null) {
            Text(
                text = summary,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

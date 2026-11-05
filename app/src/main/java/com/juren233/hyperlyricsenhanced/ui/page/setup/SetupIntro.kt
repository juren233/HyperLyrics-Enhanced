/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.setup

import com.juren233.hyperlyricsenhanced.ui.skin.SkinTheme
import com.juren233.hyperlyricsenhanced.ui.skin.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.juren233.hyperlyricsenhanced.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/*
 * 首次启动动画的时间轴（秒）。整段动画是时间的纯函数：每帧只推进时间，所有画面由下面的
 * 缓动与弹簧解析式算出，与网页原型逐帧一致；跳过时只是让时间快进。
 */
private object Timeline {
    /** 前三句整句点亮、第四句（产品名）开始的时间。 */
    val LINE_STARTS = floatArrayOf(0.15f, 0.75f, 1.35f, 1.95f)
    const val WORD_START = 2.03f
    const val WORD_STEP = 0.4f
    const val WORD_LAST_DURATION = 0.56f
    const val SUCK = 1.98f
    /**
     * 每句汇入间隔（0.34 秒）明显长于一次鼓动（0.18 秒），两句之间岛完全静止约 0.16 秒：
     * 汇入一句只鼓一下，下一句到达前不再动。鼓动与间隔相等时会首尾相接成持续抖动。
     */
    const val SUCK_STAGGER = 0.34f
    const val SUCK_DURATION = 0.5f
    const val ARRIVE_PULSE = 0.18f
    const val HERO_MOVE = 4.1f
    const val BULGE = 4.05f
    const val FALL = 4.33f
    const val LAND = 4.75f
    const val GROW = 4.83f
    const val CRISP = 5.31f
    const val EXIT = 6.05f
    const val END = EXIT + 1.1f

    /** 第 [order] 句飞到岛上的时刻：岛从这一刻开始为它回弹，歌词在这次回弹内融入消失。 */
    fun arrival(order: Int): Float = SUCK + order * SUCK_STAGGER + SUCK_DURATION
}

/** 原创英文歌词（押韵 AABBBA，第 3、5 句押产品名的 -anced），第 4 句是产品名（运行时取 app_name），其余句不随语言变化。 */
private val INTRO_LYRICS = listOf(
    "The city hums a quiet tune",
    "Glowing like a silver moon",
    "Every lyric softly danced",
    null,
    "Kept the night entranced",
    "As the dawn breaks through",
)
private const val HERO_LINE = 3

/** 汇入超级岛的顺序：先远后近。 */
private val SUCK_ORDER = intArrayOf(0, 5, 1, 4, 2)

/**
 * Apple Music 式滚动：每次换句，各行从上往下依次起步上移（每行晚 [SCROLL_STAGGER] 秒），
 * 整体像一道波浪依次上滚，而不是各行同时各滚各的。
 */
private fun scrollOffset(t: Float, line: Int, gap: Float): Float {
    var scroll = 0f
    for (j in 1 until Timeline.LINE_STARTS.size) {
        val delay = SCROLL_STAGGER * maxOf(0, line - (j - 1))
        scroll += gap * motionSpring(t, Timeline.LINE_STARTS[j] + delay, 0.9f, 140f)
    }
    return scroll
}

private const val SCROLL_STAGGER = 0.06f

/** 当前句的连续位置：换句时平滑滑向下一句，明暗与模糊都按各句与它的距离计算。 */
private fun activePosition(t: Float): Float {
    var position = 0f
    for (j in 1 until Timeline.LINE_STARTS.size) position += motionSpring(t, Timeline.LINE_STARTS[j], 1f, 150f)
    return position
}

/** 某句与当前句的距离：0 为当前句，清晰明亮；越远越暗、越模糊。 */
private fun lineDistance(t: Float, line: Int): Float = abs(line - activePosition(t))

private fun lineAlpha(distance: Float): Float = lerp(1f, 0.3f, clamp01(distance))

/** 模糊半径（dp）：当前句清晰，相邻句 3.5dp，再远 7dp 封顶。 */
private fun lineBlur(distance: Float): Float = minOf(7f, 3.5f * distance)

/** 产品名的逐字节拍：按空格与小写转大写处切分（HyperLyrics Enhanced → Hyper / Lyrics / Enhanced）。 */
private fun lyricBeats(text: String): List<IntRange> {
    val beats = mutableListOf<IntRange>()
    var start = 0
    for (index in 1 until text.length) {
        val split = text[index] == ' ' || (text[index].isUpperCase() && text[index - 1].isLowerCase())
        if (split) {
            beats += start until index
            start = if (text[index] == ' ') index + 1 else index
        }
    }
    if (start < text.length) beats += start until text.length
    return beats
}

/** 一帧里多处共用的几何（像素）。 */
private class IntroGeometry(
    val width: Float,
    val height: Float,
    val islandY: Float,
    val dp: Float,
) {
    val centerX = width / 2f
    val centerY = height * 0.42f
    val logoWidth = minOf(300f * dp, width * 0.78f)
    val logoHeight = logoWidth / ISLAND_LOGO_ASPECT
    val logo = Rect(Offset(centerX - logoWidth / 2f, centerY - logoHeight / 2f), Size(logoWidth, logoHeight))
    val lyricLeft = 28f * dp
    val lyricGap = 46f * dp
    val lyricAnchor = height * 0.36f
    val titleCenterY = centerY + logoHeight / 2f + 40f * dp
    private val restWidth = SetupStatusIsland.RestWidth.value * dp
    private val restHeight = SetupStatusIsland.RestHeight.value * dp
    private val musicWidth = SetupStatusIsland.MusicWidth.value * dp
    private val musicHeight = SetupStatusIsland.MusicHeight.value * dp

    /** 歌词飞到岛时缩到的宽度：比常驻岛宽略窄，看得清是哪一句在进岛，又刚好装进岛里。 */
    val lyricArriveWidth = restWidth * 0.8f

    /**
     * 状态栏超级岛的大小：从常驻尺寸随吸入量长到音乐态尺寸（[SetupStatusIsland]），滴落时随液滴缩回。
     * 每吸入一句只平滑地鼓一下再回落（不振荡），相邻两句的鼓动互不重叠。
     */
    fun island(t: Float): Rect {
        var bump = 0f
        var fed = 0f
        SUCK_ORDER.indices.forEach { order ->
            val arrived = Timeline.arrival(order)
            val elapsed = t - arrived
            if (elapsed > 0f && elapsed < Timeline.ARRIVE_PULSE) {
                bump = sin(PI.toFloat() * elapsed / Timeline.ARRIVE_PULSE) * 0.09f
            }
            // 每汇入一句只在这次鼓动内长大一级，两句之间尺寸不变。
            fed += motionTween(t, arrived, Timeline.ARRIVE_PULSE) / SUCK_ORDER.size
        }
        // 像水滴一样收回：液滴鼓出时岛高度不变、两侧收成圆珠，并被液滴往下拉一点；
        // 坠落时圆珠沿液滴同一条坠落曲线缩没，被液滴带走。
        val bulge = dropBulge(t)
        val fall = dropFall(t)
        val grow = motionSpring(t, 0.2f, 0.6f, 420f)
        val size = SetupStatusIsland.retract(
            Size(
                lerp(restWidth, musicWidth, fed) * grow * (1f + bump),
                lerp(restHeight, musicHeight, fed) * grow * (1f + bump * 0.5f),
            ),
            pinch = bulge,
            vanish = fall,
        )
        val sag = 3f * dp * bulge * (1f - fall)
        return Rect(Offset(centerX - size.width / 2f, islandY + sag - size.height / 2f), size)
    }

    private fun dropBulge(t: Float): Float = motionTween(t, Timeline.BULGE, 0.3f)

    private fun dropFall(t: Float): Float =
        MotionEaseIn.transform(clamp01((t - Timeline.FALL) / (Timeline.LAND - Timeline.FALL)))

    /** 液滴：从岛下鼓出 → 拉丝坠落 → 落地回弹；返回中心与两个半径。 */
    fun drop(t: Float): Pair<Offset, Size> {
        val radius = 16f * dp
        val bulge = dropBulge(t)
        val fall = dropFall(t)
        val y = lerp(lerp(islandY, islandY + 22f * dp, bulge), centerY, fall)
        var rx = radius * bulge
        var ry = radius * bulge * (1f + 0.35f * fall * (1f - fall) * 4f)
        val landed = t - Timeline.LAND
        if (landed > 0f) {
            val wobble = exp(-7f * landed) * cos(18f * landed)
            rx = radius * (1f + 0.3f * wobble)
            ry = radius * (1f - 0.26f * wobble)
        }
        return Offset(centerX, y) to Size(rx, ry)
    }

    /** 液滴落地后直接漫开成图标形状的岛身。 */
    fun body(t: Float): Rect? {
        if (t < Timeline.GROW) return null
        val radius = 16f * dp
        val grow = motionSpring(t, Timeline.GROW, 0.74f, 130f)
        val w = lerp(radius * 2f, logoWidth, grow)
        val h = lerp(radius * 2f, logoHeight, minOf(1f, grow * 1.08f))
        return Rect(Offset(centerX - w / 2f, centerY - h / 2f), Size(w, h))
    }

    /** 某句歌词（非产品名）飞向超级岛的匀速进度，1 时正好到岛上。 */
    fun suck(t: Float, line: Int): Float {
        val order = SUCK_ORDER.indexOf(line)
        if (order < 0) return 0f
        return clamp01((t - (Timeline.arrival(order) - Timeline.SUCK_DURATION)) / Timeline.SUCK_DURATION)
    }

    /** 某句歌词到岛后融入岛内的进度，与岛为它回弹的那一下同步。 */
    fun merge(t: Float, line: Int): Float {
        val order = SUCK_ORDER.indexOf(line)
        if (order < 0) return 0f
        return clamp01((t - Timeline.arrival(order)) / Timeline.ARRIVE_PULSE)
    }
}

/**
 * 首次启动动画（歌词汇流 × 液态融合）：
 * 1. 状态栏中央长出带细边框的超级岛，左侧封面、右侧音频律动；
 * 2. 原创歌词以 Apple Music 的样式依次上滚：左对齐大字，当前句清晰明亮，其余句压暗并按距离模糊；
 * 3. 唱到产品名这句时逐字演唱（品牌渐变、唱到的字轻轻上浮），同时其余歌词匀速边飞边缩到岛宽、一句接一句汇入超级岛，每句到岛后岛回弹一次；
 * 4. 产品名落到下方成为标题，岛高度不变地收成圆珠，拉丝滴下一颗液滴并随之滴走，落地回弹后直接漫开成图标，封面、歌曲信息与歌词胶囊随即弹出；
 * 5. 图标飞入引导页顶栏 [target] 的位置，细边框褪去，引导内容浮现。
 *
 * 第一次点按在底部显示“再点一次加速”的提示，再点一次才快进到结尾。[reveal] 由本动画驱动到 1，引导内容据此淡入；
 * [onReveal] 在图标开始飞向顶栏时回调，[onFinished] 在交接完成后回调。
 */
@Composable
internal fun SetupIntro(
    target: () -> LayoutCoordinates?,
    reveal: Animatable<Float, AnimationVector1D>,
    onReveal: () -> Unit,
    onFinished: () -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val palette = rememberIslandLogoPalette()
    val surface = SkinTheme.colors.surface
    val onSurface = SkinTheme.colors.onSurface
    val appName = stringResource(R.string.app_name)
    val statusBarTop = WindowInsets.statusBars.getTop(density)
    val currentOnReveal by rememberUpdatedState(onReveal)
    val currentOnFinished by rememberUpdatedState(onFinished)

    var time by remember { mutableFloatStateOf(0f) }
    var skipping by remember { mutableStateOf(false) }
    var hinted by remember { mutableStateOf(false) }
    val showHint by remember { derivedStateOf { hinted && !skipping && time < Timeline.EXIT } }
    val hintAlpha by animateFloatAsState(
        targetValue = if (showHint) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "setup_intro_skip_hint",
    )
    var ownCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var targetRect by remember { mutableStateOf<Rect?>(null) }

    val textMeasurer = rememberTextMeasurer()
    val lyricStyle = remember { TextStyle(fontSize = 25.sp, fontWeight = FontWeight.Bold) }
    val lyricLayouts = remember(appName) {
        INTRO_LYRICS.map { line -> textMeasurer.measure(line ?: appName, lyricStyle, softWrap = false) }
    }
    val heroLayout = lyricLayouts[HERO_LINE]
    val heroWords = remember(appName) {
        lyricBeats(appName).map { range ->
            heroLayout.getBoundingBox(range.first).left to
                textMeasurer.measure(appName.substring(range.first, range.last + 1), lyricStyle, softWrap = false)
        }
    }
    val wordTimes = remember(heroWords.size) {
        heroWords.indices.map { index ->
            val start = Timeline.WORD_START + index * Timeline.WORD_STEP
            val duration = if (index == heroWords.lastIndex) Timeline.WORD_LAST_DURATION else Timeline.WORD_STEP
            start to duration
        }
    }

    val gooEffect = rememberGooEffect()
    val rimColor = Color.White.copy(alpha = 0.24f).compositeOver(palette.body)

    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        val beats = wordTimes.map { it.first + it.second } + Timeline.LAND
        var revealed = false
        while (time < Timeline.END) {
            val now = withFrameNanos { it }
            val delta = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
            last = now
            val previous = time
            // 快进：跳过时以 5 倍速追到交接点，画面连续不跳变。
            val rate = if (skipping && time < Timeline.EXIT) 5f else 1f
            time = minOf(Timeline.END, time + delta * rate)
            if (!skipping) {
                beats.forEach { beat ->
                    if (previous < beat && time >= beat) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
            if (!revealed && time >= Timeline.EXIT) {
                revealed = true
                targetRect = target()?.let { header ->
                    ownCoordinates?.takeIf { it.isAttached && header.isAttached }
                        ?.localBoundingBoxOf(header, clipBounds = false)
                }
                currentOnReveal()
            }
            if (revealed) {
                reveal.snapTo(motionTween(time, Timeline.EXIT + 0.2f, 0.65f))
            }
        }
        reveal.snapTo(1f)
        currentOnFinished()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onPlaced { ownCoordinates = it }
            .semantics { contentDescription = appName }
            .pointerInput(Unit) {
                // 动画期间吞掉全部触摸，避免误触到尚未显示的引导内容；第一次点按只显示提示，再点一次才快进。
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    if (hinted) skipping = true else hinted = true
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val geometry = with(density) {
            IntroGeometry(
                width = constraints.maxWidth.toFloat(),
                height = constraints.maxHeight.toFloat(),
                islandY = SetupStatusIsland.centerY(statusBarTop, this),
                dp = 1.dp.toPx(),
            )
        }

        // 底色：交接时随引导内容浮现而淡出。
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(surface, alpha = clamp01(1f - reveal.value)) },
        )

        // 层级：歌词在最下，超级岛（液态岛身、音乐态、Logo）整体盖在歌词之上，歌词汇入时从岛下方穿过。
        // 其余歌词：每句一个图层，便于按距离模糊；汇入超级岛时边飞边缩到岛宽，进岛时才融入消失。
        val blurMargin = with(density) { 10.dp.toPx() }
        INTRO_LYRICS.indices.filter { it != HERO_LINE }.forEach { line ->
            val layout = lyricLayouts[line]
            IntroLyricLine(
                layout = layout,
                color = onSurface,
                margin = blurMargin,
                transform = { lyricLineTransform(time, line, layout, geometry, blurMargin) },
            )
        }

        // 产品名这句单独一层：唱到它之前与其他句一样按距离模糊。
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val blur = lineBlur(lineDistance(time, HERO_LINE)) * geometry.dp
                    renderEffect = if (blur > 0.3f) BlurEffect(blur, blur, TileMode.Decal) else null
                },
        ) {
            drawHeroLyric(
                t = time,
                geometry = geometry,
                layout = heroLayout,
                words = heroWords,
                wordTimes = wordTimes,
                color = onSurface,
                palette = palette,
                exitValue = introExit(time),
            )
        }

        // 液态层：先画放大一圈的浅色形状作细边框，再画岛身。
        listOf(true, false).forEach { isRim ->
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        renderEffect = gooEffect
                        alpha = 1f - motionTween(time, Timeline.CRISP, 0.24f)
                    },
            ) {
                val t = time
                val grow = if (isRim) 1f * geometry.dp else 0f
                val color = if (isRim) rimColor else palette.body
                val island = geometry.island(t)
                if (island.width > 0f) {
                    drawRoundRect(
                        color = color,
                        topLeft = island.topLeft - Offset(grow, grow),
                        size = Size(island.width + grow * 2f, island.height + grow * 2f),
                        cornerRadius = CornerRadius(island.height / 2f + grow),
                    )
                }
                val (dropCenter, dropRadius) = geometry.drop(t)
                if (dropRadius.width > 0f) {
                    drawOval(
                        color = color,
                        topLeft = dropCenter - Offset(dropRadius.width + grow, dropRadius.height + grow),
                        size = Size((dropRadius.width + grow) * 2f, (dropRadius.height + grow) * 2f),
                    )
                }
                geometry.body(t)?.let { body ->
                    drawRoundRect(
                        color = color,
                        topLeft = body.topLeft - Offset(grow, grow),
                        size = Size(body.width + grow * 2f, body.height + grow * 2f),
                        cornerRadius = CornerRadius(minOf(body.width, body.height) / 2f + grow),
                    )
                }
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            val t = time
            val exitValue = introExit(t)
            val landed = targetRect
            val rect = if (landed != null) lerpRect(geometry.logo, landed, exitValue) else geometry.logo
            val logoAlpha = if (landed != null) 1f else clamp01(1f - exitValue)
            val crisp = motionTween(t, Timeline.CRISP, 0.24f)

            drawIslandMusicState(geometry.island(t), t, palette, fade = 1f - motionTween(t, Timeline.BULGE - 0.12f, 0.26f))

            // 封面与歌词胶囊位置的两团光晕，随呼吸缓慢明灭。
            val glowAlpha = 0.32f * (0.85f + 0.15f * sin(t * 2.6f)) *
                motionTween(t, Timeline.GROW + 0.1f, 0.4f) * clamp01(1f - exitValue)
            if (glowAlpha > 0f) {
                val radius = geometry.logoWidth * 0.6f
                listOf(
                    palette.accentStart to islandLogoCover(rect).center,
                    palette.accentEnd to islandLogoLyric(rect).center,
                ).forEach { (color, at) ->
                    drawCircle(
                        brush = Brush.radialGradient(listOf(color, Color.Transparent), center = at, radius = radius),
                        radius = radius,
                        center = at,
                        alpha = glowAlpha,
                    )
                }
            }

            if (t >= Timeline.CRISP - 0.1f) {
                drawIslandLogo(
                    topLeft = rect.topLeft,
                    size = rect.size,
                    palette = palette,
                    cover = motionSpring(t, Timeline.CRISP - 0.1f, 0.5f, 320f),
                    songTitle = motionSpring(t, Timeline.CRISP - 0.02f, 0.55f, 300f),
                    songArtist = motionSpring(t, Timeline.CRISP + 0.05f, 0.55f, 300f),
                    lyric = motionSpring(t, Timeline.CRISP - 0.04f, 0.7f, 260f),
                    lyricAlpha = motionTween(t, Timeline.CRISP - 0.08f, 0.12f),
                    alpha = logoAlpha,
                    bodyAlpha = crisp,
                    rim = 1f - exitValue,
                )
            }
        }

        Text(
            text = stringResource(R.string.setup_intro_skip_hint),
            fontSize = 13.sp,
            color = SkinTheme.colors.onSurfaceSecondary,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 24.dp)
                .graphicsLayer { alpha = hintAlpha },
        )
    }
}

/** 图标飞向顶栏、产品名淡出的交接进度。 */
private fun introExit(t: Float): Float = motionSpring(t, Timeline.EXIT, 0.88f, 120f)

/** 一句歌词的图层变换：位置（左上角）、缩放、透明度与模糊半径。 */
private class LyricTransform(val x: Float, val y: Float, val scale: Float, val alpha: Float, val blur: Float)

private fun lyricLineTransform(
    t: Float,
    line: Int,
    layout: TextLayoutResult,
    geometry: IntroGeometry,
    margin: Float,
): LyricTransform {
    val width = layout.size.width.toFloat()
    val height = layout.size.height.toFloat()
    val centerY = geometry.lyricAnchor + line * geometry.lyricGap - scrollOffset(t, line, geometry.lyricGap)
    val distance = lineDistance(t, line)
    var alpha = lineAlpha(distance) * motionTween(t, 0f, 0.6f)
    var blur = lineBlur(distance) * geometry.dp
    var centerX = geometry.lyricLeft + width / 2f
    var y = centerY
    var scale = 1f
    val raw = geometry.suck(t, line)
    if (raw > 0f) {
        // 匀速飞行，一路只缩到岛宽，并变清晰、不透明，看得见是哪一句在进岛；
        // 到岛上之后才在岛这一下回弹里融入消失。
        val merge = geometry.merge(t, line)
        val arriveScale = minOf(1f, geometry.lyricArriveWidth / width)
        val swing = if (line % 2 == 1) 16f else -16f
        centerX = lerp(centerX, geometry.centerX, raw) + sin(raw * PI.toFloat()) * swing * geometry.dp
        y = lerp(centerY, geometry.islandY, raw)
        scale = lerp(1f, arriveScale, raw) * lerp(1f, 0.6f, merge)
        alpha = lerp(lerp(alpha, 1f, raw), 0f, merge)
        blur *= 1f - raw
    }
    return LyricTransform(
        x = centerX - width / 2f - margin,
        y = y - height / 2f - margin,
        scale = scale,
        alpha = alpha,
        blur = blur,
    )
}

@Composable
private fun IntroLyricLine(
    layout: TextLayoutResult,
    color: Color,
    margin: Float,
    transform: () -> LyricTransform,
) {
    val density = LocalDensity.current
    val width = with(density) { (layout.size.width + margin * 2f).toDp() }
    val height = with(density) { (layout.size.height + margin * 2f).toDp() }
    Box(
        Modifier
            .size(width, height)
            .graphicsLayer {
                val value = transform()
                translationX = value.x
                translationY = value.y
                scaleX = value.scale
                scaleY = value.scale
                alpha = value.alpha
                renderEffect = if (value.blur > 0.3f) BlurEffect(value.blur, value.blur, TileMode.Decal) else null
            }
            .drawBehind { drawText(layout, color = color, topLeft = Offset(margin, margin)) },
    )
}

/**
 * 状态栏超级岛的音乐态：左侧封面、右侧音频律动；岛长出时随高度显现，收回时随岛变短（两侧收向圆）淡出；
 * [fade] 控制整体显隐。
 *
 * 给出 [coverFrom]（及其圆角 [coverFromRadius]）时，封面不随 [fade] 显现，而是从这块已经显示着的封面
 * 按 [coverDock] 连续移到岛封面的位置：完成转场里图标的封面直接成为岛的封面，中间不消失。
 */
internal fun DrawScope.drawIslandMusicState(
    island: Rect,
    t: Float,
    palette: IslandLogoPalette,
    fade: Float,
    coverFrom: Rect? = null,
    coverFromRadius: Float = 0f,
    coverDock: Float = 1f,
) {
    if (island.height <= 0f) return
    // 长出：岛从无到有长高时显现（只用于 intro，完成转场的岛从顶栏小图标起飞，不能按高度判断）；
    // 收回：两侧收向圆时淡出，图标与音乐态的长宽比都在显示范围内。
    val growing = clamp01((island.height / SetupStatusIsland.RestHeight.toPx() - 0.6f) / 0.4f)
    val retracting = clamp01((island.width / island.height - 1.5f) / 1.2f)
    val visible = growing * retracting * fade
    val inset = 5.5.dp.toPx()
    val cover = island.height - inset * 2f
    // 左侧留得比上下宽一些，封面不贴着胶囊圆角。
    val coverLeft = 9.dp.toPx() * clamp01(island.height / SetupStatusIsland.RestHeight.toPx())
    val islandCover = Rect(Offset(island.left + coverLeft, island.top + inset), Size(cover, cover))
    val coverRect = if (coverFrom != null) lerpRect(coverFrom, islandCover, coverDock) else islandCover
    val coverRadius = if (coverFrom != null) lerp(coverFromRadius, cover * 0.3f, coverDock) else cover * 0.3f
    val coverVisible = if (coverFrom != null) retracting else visible
    if (coverVisible > 0f) {
        scale(scale = coverVisible, pivot = coverRect.center) {
            drawRoundRect(
                brush = Brush.linearGradient(listOf(palette.accentStart, palette.accentEnd), coverRect.bottomLeft, coverRect.topRight),
                topLeft = coverRect.topLeft,
                size = coverRect.size,
                cornerRadius = CornerRadius(coverRadius),
                alpha = coverVisible,
            )
        }
    }
    if (visible <= 0f) return
    // 四根律动条，各自相位与节奏不同，起伏放缓到接近真实音乐律动；高度随岛身一起缩放。
    val barWidth = 1.8.dp.toPx()
    val barGap = 2.2.dp.toPx()
    val maxBar = island.height - 14.dp.toPx()
    val right = island.right - 10.dp.toPx()
    val phases = floatArrayOf(0f, 1.7f, 0.9f, 2.6f)
    val speeds = floatArrayOf(4.8f, 3.7f, 5.6f, 4.2f)
    phases.indices.forEach { index ->
        val level = 0.3f + 0.7f * abs(sin(t * speeds[index] + phases[index]))
        val barHeight = maxOf(barWidth, maxBar * level) * visible
        val x = right - (phases.size - index) * barWidth - (phases.size - 1 - index) * barGap
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(palette.accentStart, palette.accentEnd),
                startY = island.center.y - maxBar / 2f,
                endY = island.center.y + maxBar / 2f,
            ),
            topLeft = Offset(x, island.center.y - barHeight / 2f),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(barWidth / 2f),
            alpha = visible,
        )
    }
}

/**
 * 产品名这句：逐字演唱。每个字内用品牌渐变柔和地由暗变亮，唱到的字轻轻上浮；
 * 唱完后落到图标下方成为标题，交接时淡出上移。
 */
private fun DrawScope.drawHeroLyric(
    t: Float,
    geometry: IntroGeometry,
    layout: TextLayoutResult,
    words: List<Pair<Float, TextLayoutResult>>,
    wordTimes: List<Pair<Float, Float>>,
    color: Color,
    palette: IslandLogoPalette,
    exitValue: Float,
) {
    val alpha = motionTween(t, 0f, 0.6f) * clamp01(1f - exitValue * 2.2f)
    if (alpha <= 0f) return
    val lineWidth = layout.size.width.toFloat()
    val lineHeight = layout.size.height.toFloat()
    val move = motionSpring(t, Timeline.HERO_MOVE, 0.82f, 120f)
    val scale = lerp(1f, 24f / 25f, move)
    val lineCenterY = geometry.lyricAnchor + HERO_LINE * geometry.lyricGap - scrollOffset(t, HERO_LINE, geometry.lyricGap)
    val titleCenterY = geometry.titleCenterY - exitValue * 24f * geometry.dp
    val left = lerp(geometry.lyricLeft, geometry.centerX - lineWidth * scale / 2f, move)
    val top = lerp(lineCenterY, titleCenterY, move) - lineHeight * scale / 2f
    // 未唱到的字：唱到这句前与其他句同样压暗，成为当前句后略微提亮。
    val base = lerp(0.42f, 0.3f, clamp01(lineDistance(t, HERO_LINE)))
    val soft = 0.22f
    val dp = geometry.dp

    withTransform({
        translate(left, top)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        words.forEachIndexed { index, (x, word) ->
            val (start, duration) = wordTimes[index]
            val progress = motionTween(t, start, duration)
            val lift = -3f * dp * motionSpring(t, start, 0.6f, 300f) * (1f - move)
            val at = Offset(x, lift)
            // 品牌渐变贯穿整句：坐标按该词在整句中的位置偏移。
            val brand = Brush.horizontalGradient(
                listOf(palette.accentStart, palette.accentEnd),
                startX = -x,
                endX = lineWidth - x,
            )
            if (progress < 1f) {
                drawText(word, color = color.copy(alpha = base), topLeft = at, alpha = alpha)
            }
            if (progress >= 1f) {
                drawText(word, brush = brand, topLeft = at, alpha = alpha)
            } else if (progress > 0f) {
                val wordWidth = word.size.width.toFloat()
                val bounds = Rect(at, Size(wordWidth, word.size.height.toFloat())).inflate(4f * dp)
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(bounds, Paint())
                    drawText(word, brush = brand, topLeft = at, alpha = alpha)
                    // 字内柔和渐亮：唱到的位置之前不透明，之后透明，交界宽度为词宽的 22%。
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Black, Color.Transparent),
                            startX = at.x + wordWidth * (progress * (1f + soft) - soft),
                            endX = at.x + wordWidth * progress * (1f + soft),
                        ),
                        topLeft = bounds.topLeft,
                        size = bounds.size,
                        blendMode = BlendMode.DstIn,
                    )
                    canvas.restore()
                }
            }
        }
    }
}

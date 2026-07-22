package com.juren233.hyperlyricsenhanced.lyric.view.line

import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.text.TextPaint
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.view.line.model.LyricModel

internal const val INTERLUDE_EXIT_DURATION_MS = 1_750L

internal class InterludeDotsRenderer {
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var playbackPositionMs = Long.MIN_VALUE
    private var positionUpdatedAtMs = 0L
    private var playbackActive = true

    fun isIndicator(model: LyricModel): Boolean =
        model.metadata?.getBoolean(LyricMetadataKeys.INSTRUMENTAL) == true

    fun reset() {
        playbackPositionMs = Long.MIN_VALUE
        positionUpdatedAtMs = 0L
        playbackActive = true
    }

    fun updatePosition(positionMs: Long, nowMs: Long = SystemClock.uptimeMillis()) {
        playbackPositionMs = positionMs
        positionUpdatedAtMs = nowMs
    }

    fun setPlaybackActive(active: Boolean, nowMs: Long = SystemClock.uptimeMillis()) {
        if (playbackActive == active) return
        if (!active && playbackPositionMs != Long.MIN_VALUE) {
            playbackPositionMs += nowMs - positionUpdatedAtMs
        }
        positionUpdatedAtMs = nowMs
        playbackActive = active
    }

    fun width(textSize: Float): Float =
        dotSize(textSize) * DOTS_VISUAL_WIDTH_IN_DIAMETERS * MAX_GROUP_SCALE

    fun draw(
        canvas: Canvas,
        model: LyricModel,
        textPaint: TextPaint,
        viewWidth: Int,
        viewHeight: Int,
        centerIfPossible: Boolean,
        nowMs: Long = SystemClock.uptimeMillis()
    ) {
        val positionMs = resolvePlaybackPosition(model, nowMs)
        val frame = resolveInterludeDotsFrame(positionMs, model.begin, model.end)

        val diameter = dotSize(textPaint.textSize)
        val radius = diameter / 2f
        val step = diameter * DOT_STEP_IN_DIAMETERS
        val totalWidth = width(textPaint.textSize)
        val startX = resolveInterludeDotsStartX(
            totalWidth = totalWidth,
            viewWidth = viewWidth.toFloat(),
            isAlignedRight = model.isAlignedRight,
            centerIfPossible = centerIfPossible
        )
        val centerY = viewHeight / 2f
        val groupCenterX = startX + totalWidth / 2f

        dotPaint.color = textPaint.color
        dotPaint.shader = textPaint.shader

        repeat(DOT_COUNT) { index ->
            dotPaint.alpha = frame.dotAlphas[index] * frame.groupAlpha / 255
            canvas.drawCircle(
                groupCenterX + (index - 1) * step * frame.groupScale,
                centerY,
                radius * frame.groupScale,
                dotPaint
            )
        }
    }

    private fun resolvePlaybackPosition(model: LyricModel, nowMs: Long): Long {
        if (playbackPositionMs == Long.MIN_VALUE) {
            playbackPositionMs = model.begin
            positionUpdatedAtMs = nowMs
        }
        val extrapolated = if (playbackActive) {
            playbackPositionMs + nowMs - positionUpdatedAtMs
        } else {
            playbackPositionMs
        }
        return extrapolated.coerceIn(model.begin, maxOf(model.begin, model.end))
    }

    private fun dotSize(textSize: Float): Float = resolveInterludeDotSize(textSize)

    private companion object {
        const val DOT_COUNT = 3
        const val DOT_STEP_IN_DIAMETERS = 1.6f
        const val DOTS_VISUAL_WIDTH_IN_DIAMETERS = 4.2f
        const val MAX_GROUP_SCALE = 1.4f
    }
}

internal fun resolveInterludeDotSize(textSize: Float): Float =
    textSize * 0.45f

internal data class InterludeDotsFrame(
    val dotAlphas: List<Int>,
    val groupScale: Float,
    val groupAlpha: Int = 255
)

internal fun resolveInterludeDotsFrame(
    positionMs: Long,
    beginMs: Long,
    endMs: Long
): InterludeDotsFrame {
    val durationMs = maxOf(1L, endMs - beginMs)
    val elapsedMs = (positionMs - beginMs).coerceIn(0L, durationMs)
    val exitStartMs = maxOf(1L, durationMs - INTERLUDE_EXIT_DURATION_MS)
    val startFrame = resolveInterludeActiveFrame(exitStartMs, exitStartMs)

    return if (elapsedMs < exitStartMs) {
        resolveInterludeActiveFrame(elapsedMs, exitStartMs)
    } else {
        resolveInterludeExitFrame(startFrame, elapsedMs - exitStartMs)
    }
}

private fun resolveInterludeActiveFrame(
    elapsedMs: Long,
    activeDurationMs: Long
): InterludeDotsFrame {
    val dotStepMs = (activeDurationMs + INTERLUDE_FINAL_DOT_COMPLETION_MS) / 3f

    val dotAlphas = List(3) { index ->
        val startMs = index * dotStepMs
        val phaseDurationMs = if (index < 2) {
            dotStepMs
        } else {
            maxOf(1f, activeDurationMs - startMs)
        }
        val rawProgress = ((elapsedMs - startMs) / phaseDurationMs).coerceIn(0f, 1f)
        val appleTargetFraction = (phaseDurationMs / dotStepMs).coerceIn(0f, 1f)
        lerp(
            DOTS_DIM_ALPHA.toFloat(),
            DOTS_FULL_ALPHA.toFloat(),
            smootherStep(rawProgress) * appleTargetFraction
        ).toInt()
    }

    return InterludeDotsFrame(
        dotAlphas = dotAlphas,
        groupScale = resolveBreathingScale(elapsedMs)
    )
}

internal fun resolveInterludeExitFrame(
    startFrame: InterludeDotsFrame,
    elapsedMs: Long
): InterludeDotsFrame {
    val elapsed = elapsedMs.coerceIn(0L, INTERLUDE_EXIT_DURATION_MS)
    return when {
        elapsed < INTERLUDE_FINAL_DOT_COMPLETION_MS -> {
            val progress = smootherStep(elapsed.toFloat() / INTERLUDE_FINAL_DOT_COMPLETION_MS)
            startFrame.copy(
                dotAlphas = startFrame.dotAlphas.map {
                    lerp(it.toFloat(), DOTS_FULL_ALPHA.toFloat(), progress).toInt()
                }
            )
        }

        elapsed < INTERLUDE_FINAL_DOT_COMPLETION_MS + INTERLUDE_EXIT_GROW_MS -> {
            val progress = smootherStep(
                (elapsed - INTERLUDE_FINAL_DOT_COMPLETION_MS).toFloat() / INTERLUDE_EXIT_GROW_MS
            )
            InterludeDotsFrame(
                dotAlphas = List(3) { DOTS_FULL_ALPHA },
                groupScale = lerp(startFrame.groupScale, DOTS_MAX_SCALE, progress)
            )
        }

        else -> {
            val progress = easeInCubic(
                (elapsed - INTERLUDE_FINAL_DOT_COMPLETION_MS - INTERLUDE_EXIT_GROW_MS)
                    .toFloat() / INTERLUDE_EXIT_COLLAPSE_MS
            )
            InterludeDotsFrame(
                dotAlphas = List(3) { DOTS_FULL_ALPHA },
                groupScale = lerp(DOTS_MAX_SCALE, DOTS_EXIT_SCALE, progress),
                groupAlpha = lerp(255f, 0f, progress).toInt()
            )
        }
    }
}

private const val INTERLUDE_FINAL_DOT_COMPLETION_MS = 750L
private const val INTERLUDE_EXIT_GROW_MS = 750L
private const val INTERLUDE_EXIT_COLLAPSE_MS = 250L
private const val INTERLUDE_BREATH_DURATION_MS = 4_000L
private const val DOTS_DIM_ALPHA = 49
private const val DOTS_FULL_ALPHA = 255
private const val DOTS_MAX_SCALE = 1.4f
private const val DOTS_EXIT_SCALE = 0.5f

private fun resolveBreathingScale(elapsedMs: Long): Float {
    val phase = (elapsedMs % INTERLUDE_BREATH_DURATION_MS).toFloat() /
        INTERLUDE_BREATH_DURATION_MS
    val pulse = if (phase <= 0.5f) {
        smootherStep(phase * 2f)
    } else {
        1f - smootherStep((phase - 0.5f) * 2f)
    }
    return lerp(1f, DOTS_MAX_SCALE, pulse)
}

private fun smootherStep(value: Float): Float {
    val clamped = value.coerceIn(0f, 1f)
    return clamped * clamped * clamped * (clamped * (clamped * 6f - 15f) + 10f)
}

private fun easeInCubic(value: Float): Float {
    val clamped = value.coerceIn(0f, 1f)
    return clamped * clamped * clamped
}

private fun lerp(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction.coerceIn(0f, 1f)

internal fun resolveInterludeDotsStartX(
    totalWidth: Float,
    viewWidth: Float,
    isAlignedRight: Boolean,
    centerIfPossible: Boolean
): Float = when {
    totalWidth >= viewWidth -> 0f
    isAlignedRight -> viewWidth - totalWidth
    centerIfPossible -> (viewWidth - totalWidth) / 2f
    else -> 0f
}

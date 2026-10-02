/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

/** Shared capacity, scroll range and drawing geometry for both SpaceGate renderers. */
internal class SpaceGateLineLayout(
    textWidth: Float,
    private val viewWidth: Float,
    private val layout: SeamOcclusionLayout?,
    private val seam: Float,
    private val isAlignedRight: Boolean = false,
    private val centerIfPossible: Boolean = false,
    private val alignRight: Boolean = false,
    private val nextLineOnRight: Boolean = false,
) {
    private val naturalWidth = maxOf(textWidth, layout?.totalAdvance ?: 0f)
    private val validSeam = layout != null && seam > 0f && seam < viewWidth
    private val previewTravel = if (nextLineOnRight && (validSeam || layout == null && seam == 0f)) {
        SpaceGateRightPreviewGeometry.travel(naturalWidth, viewWidth, seam)
    } else 0f

    // When HEAD avoidance exceeds the spare room, the same word also crosses the
    // seam at every origin between left and right alignment. Neither side can
    // display the whole line statically. Do not fall back to splitting letters.
    private val capacityWord = if (validSeam && naturalWidth <= viewWidth) {
        layout?.straddlingStaticUnit(0f, seam)?.takeIf {
            naturalWidth + seam - it.start > viewWidth + SeamOcclusionLayout.FUZZ
        }
    } else null

    /** Scroll extent only; never feed its additional travel into lyric timing. */
    val scrollWidth: Float = if (previewTravel > 0f) viewWidth + previewTravel
        else capacityWord?.let { viewWidth + it.width } ?: naturalWidth
    val isOverflow: Boolean get() = scrollWidth > viewWidth

    fun textOrigin(scrollOffset: Float): Float = when {
        isOverflow -> scrollOffset
        alignRight -> viewWidth - naturalWidth
        centerIfPossible -> (viewWidth - naturalWidth) / 2f
        isAlignedRight -> viewWidth - naturalWidth
        else -> 0f
    }

    fun plan(scrollOffset: Float): SeamStripPlan? {
        if (!validSeam) return null
        val seamLayout = requireNotNull(layout)
        val origin = textOrigin(scrollOffset)
        if (previewTravel > 0f) {
            return SeamStripPlan.scrollFromHead(seamLayout, origin, seam, previewTravel)
        }
        capacityWord?.let {
            return SeamStripPlan.capacityScroll(
                seamLayout, origin, seam, it, viewWidth - naturalWidth,
            )
        }
        return if (isOverflow) {
            SeamStripPlan.scrollWithEndpoints(seamLayout, origin, seam, 0f, -(scrollWidth - viewWidth))
        } else {
            val anchor = when {
                alignRight -> SeamStripPlan.Anchor.TAIL
                centerIfPossible -> SeamStripPlan.Anchor.FREE
                isAlignedRight -> SeamStripPlan.Anchor.TAIL
                else -> SeamStripPlan.Anchor.HEAD
            }
            SeamStripPlan.staticClear(seamLayout, origin, seam, anchor, viewWidth)
        }
    }

    fun followProgress(progress: Float, preferredAnchor: Float): SeamStripPlan.ProgressFollow? {
        if (!validSeam || !isOverflow) return null
        val seamLayout = requireNotNull(layout)
        if (previewTravel > 0f) {
            return SeamStripPlan.followFromHead(
                seamLayout, progress, seam, previewTravel,
                SpaceGateRightPreviewGeometry.anchor(viewWidth, seam),
            )
        }
        capacityWord?.let { word ->
            // One continuous progress interval, including word boundaries. Solving
            // each band independently would jump when the highlight crosses a gap.
            val start = (preferredAnchor - (seam - word.start))
                .coerceIn(word.start, word.end)
            val end = minOf(start + word.width, seamLayout.totalAdvance)
            val fraction = ((progress - start) / (end - start)).coerceIn(0f, 1f)
            // Use the same subtraction as ScrollStepper/stop-at-end; (view +
            // wordWidth) - view can differ from wordWidth by a floating-point ULP.
            return SeamStripPlan.ProgressFollow(-(scrollWidth - viewWidth) * fraction, preferredAnchor)
        }
        return SeamStripPlan.followProgress(
            seamLayout, progress, naturalWidth, viewWidth, seam, preferredAnchor,
        )
    }
}

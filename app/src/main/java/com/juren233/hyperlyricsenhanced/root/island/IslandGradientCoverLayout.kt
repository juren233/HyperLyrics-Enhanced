package com.juren233.hyperlyricsenhanced.root.island

import kotlin.math.roundToInt

internal data class IslandGradientPlacement(
    val coverWidth: Int,
    val coverHeight: Int,
    val iconScaleX: Float,
    val iconScaleY: Float,
    val iconTranslationX: Float,
    val iconTranslationY: Float,
    val gradientBandFraction: Float,
)

internal data class IslandGradientGeometryCandidate(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

internal data class IslandCenterCropWindow(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal object IslandGradientCoverRuntimeIdentifiers {
    // Verified from the original miui-systemui-plugin.apk DEX descriptors.
    const val FAKE_CONTENT_VIEW_CLASS =
        "miui.systemui.dynamicisland.window.content.DynamicIslandContentFakeView"
    const val PHONE_HELPER_CLASS =
        "miui.systemui.dynamicisland.window.content.helpers.DynamicIslandContentViewPhoneHelper"
    const val CONTENT_VIEW_HOLDER_CLASS =
        "miui.systemui.dynamicisland.model.IslandContentViewHolder"
    const val SMALL_ISLAND_STATE_CLASS =
        "miui.systemui.dynamicisland.event.DynamicIslandState\$SmallIsland"
    const val BIG_ISLAND_STATE_CLASS =
        "miui.systemui.dynamicisland.event.DynamicIslandState\$BigIsland"
    const val FIND_AND_INIT_VIEWS_METHOD = "findAndInitViews"

    fun isSmallIslandState(className: String?): Boolean {
        return className == SMALL_ISLAND_STATE_CLASS
    }

    fun compactIslandRole(className: String?): Boolean? = when (className) {
        SMALL_ISLAND_STATE_CLASS -> true
        BIG_ISLAND_STATE_CLASS -> false
        else -> null
    }
}

internal object IslandGradientCoverLayout {
    private const val GRADIENT_BAND_DP = 10f
    private const val GRADIENT_BAND_MAX_FRACTION = 0.32f
    private const val DEFAULT_ICON_SIZE_DP = 24f
    private const val EMBEDDED_TRANSITION_OVERLAP_DP = 3f
    private const val EMBEDDED_TRANSITION_HOLD_DP = 6f
    private const val EMBEDDED_TRANSITION_EXTENSION_DP = 72f
    private const val EMBEDDED_TRANSITION_DIFFUSION_RADIUS_DP = 10f
    private const val EMBEDDED_TRANSITION_DIFFUSION_HEIGHT_FRACTION = 0.26f
    private const val EMBEDDED_TRANSITION_DIFFUSION_START_FRACTION = 0.45f
    private const val EMBEDDED_TRANSITION_BLUR_RADIUS_X_DP = 2f
    private const val EMBEDDED_TRANSITION_BLUR_RADIUS_Y_DP = 5f
    private const val EMBEDDED_TRANSITION_INSET_DP = 3f
    // The complete blur ramp lives inside the cover: 0% at 8dp inward, 100% at its edge.
    private const val EMBEDDED_TRANSITION_BLUR_INSET_DP = 8f
    private const val EMBEDDED_TRANSITION_BLUR_EDGE_ALPHA = 1f
    private const val EMBEDDED_TRANSITION_BLUR_FULL_AFTER_EDGE_DP = 0f

    fun resolve(
        moduleWidth: Int,
        moduleHeight: Int,
        moduleWindowY: Float,
        islandWindowX: Float,
        iconWindowX: Float,
        iconWindowY: Float,
        iconWidth: Int,
        iconHeight: Int,
        isSmallIsland: Boolean,
        density: Float,
    ): IslandGradientPlacement? {
        if (moduleHeight <= 0 || iconWidth <= 0 || iconHeight <= 0 || density <= 0f) return null

        val coverWidth = if (isSmallIsland && moduleWidth > 0) moduleWidth else moduleHeight
        val coverHeight = moduleHeight
        val gradientBandFraction = gradientBandFraction(coverWidth, density)

        return IslandGradientPlacement(
            coverWidth = coverWidth,
            coverHeight = coverHeight,
            iconScaleX = coverWidth.toFloat() / iconWidth,
            iconScaleY = coverHeight.toFloat() / iconHeight,
            iconTranslationX = islandWindowX - iconWindowX,
            iconTranslationY = moduleWindowY - iconWindowY,
            gradientBandFraction = gradientBandFraction,
        )
    }

    fun gradientBandFraction(coverWidth: Int, density: Float): Float {
        if (coverWidth <= 0 || density <= 0f) return 0f
        val maxBand = (coverWidth * GRADIENT_BAND_MAX_FRACTION)
            .roundToInt()
            .coerceAtLeast(1)
        val gradientBandWidth = (GRADIENT_BAND_DP * density).roundToInt().coerceIn(1, maxBand)
        return gradientBandWidth.toFloat() / coverWidth
    }

    /**
     * Resolve the ImageView's layout-space size. Drawable intrinsic pixels are deliberately
     * excluded: album artwork is commonly 512 px while the host ImageView is a 24 dp icon.
     * Using the bitmap size before the View is measured shrinks the first gradient frame.
     */
    fun resolveIconDimension(
        actualSize: Int,
        measuredSize: Int,
        layoutParamSize: Int,
        minimumSize: Int,
        density: Float,
    ): Int {
        return actualSize.takeIf { it > 0 }
            ?: measuredSize.takeIf { it > 0 }
            ?: layoutParamSize.takeIf { it > 0 }
            ?: minimumSize.takeIf { it > 0 }
            ?: (DEFAULT_ICON_SIZE_DP * density).roundToInt().coerceAtLeast(1)
    }

    fun embeddedTransitionOverlap(coverWidth: Float, density: Float): Float {
        if (coverWidth <= 0f || density <= 0f) return 0f
        return minOf(EMBEDDED_TRANSITION_OVERLAP_DP * density, coverWidth / 5f)
    }

    fun embeddedTransitionInset(coverWidth: Float, density: Float): Float {
        if (coverWidth <= 0f || density <= 0f) return 0f
        // Keep only enough source texture inside the clear cover to hide the host background seam.
        return minOf(EMBEDDED_TRANSITION_INSET_DP * density, coverWidth / 2f)
    }

    fun embeddedTransitionBlurInset(coverWidth: Float, density: Float): Float {
        if (coverWidth <= 0f || density <= 0f) return 0f
        return minOf(EMBEDDED_TRANSITION_BLUR_INSET_DP * density, coverWidth / 2f)
    }

    fun embeddedTransitionCacheInset(coverWidth: Float, density: Float): Float {
        return maxOf(
            embeddedTransitionInset(coverWidth, density),
            embeddedTransitionBlurInset(coverWidth, density),
        )
    }

    fun embeddedTransitionRawCacheOffset(
        transitionInset: Float,
        blurInset: Float,
    ): Float {
        return (maxOf(transitionInset, blurInset) - transitionInset).coerceAtLeast(0f)
    }

    fun embeddedTransitionBlurEdgeAlpha(): Float {
        return EMBEDDED_TRANSITION_BLUR_EDGE_ALPHA
    }

    fun embeddedTransitionBlurFullAfterEdge(density: Float): Float {
        if (density <= 0f) return 0f
        return EMBEDDED_TRANSITION_BLUR_FULL_AFTER_EDGE_DP * density
    }

    fun embeddedTransitionMaxExtension(density: Float): Float {
        if (density <= 0f) return 0f
        return EMBEDDED_TRANSITION_EXTENSION_DP * density
    }

    fun embeddedTransitionHold(density: Float): Float {
        if (density <= 0f) return 0f
        return EMBEDDED_TRANSITION_HOLD_DP * density
    }

    fun embeddedTransitionVisibleExtension(availableWidth: Float, density: Float): Float {
        return minOf(
            embeddedTransitionMaxExtension(density),
            availableWidth.coerceAtLeast(0f),
        )
    }

    /**
     * The edge-smear texture and shade caches must not depend on the animated host width. Canvas
     * scales the fixed cache into the currently visible band, avoiding bitmap copies, pixel
     * sampling, or diffusion reconstruction in every width-animation frame.
     */
    fun embeddedTransitionBitmapWidth(coverWidth: Float, density: Float): Int {
        val width = embeddedTransitionCacheInset(coverWidth, density) +
            embeddedTransitionMaxExtension(density)
        return width.roundToInt().coerceAtLeast(1)
    }

    fun embeddedTransitionFeatherAlpha(position: Float, overlap: Float): Float {
        if (overlap <= 0f) return 1f
        return smootherStep((position / overlap).coerceIn(0f, 1f))
    }

    fun embeddedTransitionBlackMix(
        position: Float,
        totalWidth: Float,
        overlap: Float,
        hold: Float,
    ): Float {
        if (totalWidth <= 0f) return 1f
        val fadeStart = (overlap + hold).coerceIn(0f, totalWidth)
        if (fadeStart >= totalWidth) return if (position >= totalWidth) 1f else 0f
        val progress = ((position - fadeStart) / (totalWidth - fadeStart)).coerceIn(0f, 1f)
        return smootherStep(progress)
    }

    fun embeddedTransitionEdgeSourceX(
        position: Float,
        overlap: Float,
        cropRight: Int,
        sourceOverlap: Float,
    ): Float {
        val edgeX = cropRight - 1f
        if (position >= overlap || overlap <= 0f) return edgeX
        val progress = (position / overlap).coerceIn(0f, 1f)
        return edgeX - sourceOverlap.coerceAtLeast(0f) * (1f - progress)
    }

    fun embeddedTransitionDiffusionRadius(
        position: Float,
        totalWidth: Float,
        overlap: Float,
        targetHeight: Int,
        density: Float,
    ): Float {
        val diffusionStart = embeddedTransitionDiffusionStart(overlap)
        if (totalWidth <= diffusionStart || targetHeight <= 0 || density <= 0f) return 0f
        val progress = ((position - diffusionStart) / (totalWidth - diffusionStart))
            .coerceIn(0f, 1f)
        val maximum = minOf(
            EMBEDDED_TRANSITION_DIFFUSION_RADIUS_DP * density,
            targetHeight * EMBEDDED_TRANSITION_DIFFUSION_HEIGHT_FRACTION,
        )
        return maximum * smootherStep(progress)
    }

    fun embeddedTransitionDiffusionStart(overlap: Float): Float {
        if (overlap <= 0f) return 0f
        return overlap * EMBEDDED_TRANSITION_DIFFUSION_START_FRACTION
    }

    fun embeddedTransitionDiffusionBlend(position: Float, overlap: Float): Float {
        if (overlap <= 0f) return 1f
        val start = embeddedTransitionDiffusionStart(overlap)
        if (overlap <= start) return 1f
        return smootherStep(((position - start) / (overlap - start)).coerceIn(0f, 1f))
    }

    /** CPU blur radii used once while the fixed edge-smear cache is generated. */
    fun embeddedTransitionBlurRadiusX(density: Float): Float {
        if (density <= 0f) return 0f
        return EMBEDDED_TRANSITION_BLUR_RADIUS_X_DP * density
    }

    fun embeddedTransitionBlurRadiusY(density: Float): Float {
        if (density <= 0f) return 0f
        return EMBEDDED_TRANSITION_BLUR_RADIUS_Y_DP * density
    }

    fun embeddedTransitionBlurProgress(
        position: Float,
        totalWidth: Float,
        blurInset: Float,
        density: Float,
    ): Float {
        if (totalWidth <= 0f || density <= 0f) return 0f
        val edge = blurInset.coerceIn(0f, totalWidth)
        val edgeAlpha = embeddedTransitionBlurEdgeAlpha().coerceIn(0f, 1f)
        if (edge > 0f && position <= edge) {
            return edgeAlpha * smootherStep(position / edge)
        }

        val fullAfterEdge = embeddedTransitionBlurFullAfterEdge(density)
            .coerceAtMost((totalWidth - edge).coerceAtLeast(0f))
        if (fullAfterEdge <= 0f) return if (position >= edge) 1f else 0f
        val afterEdge = smootherStep((position - edge) / fullAfterEdge)
        return edgeAlpha + (1f - edgeAlpha) * afterEdge
    }

    private fun smootherStep(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return t * t * t * (t * (t * 6f - 15f) + 10f)
    }

    fun centerCropWindow(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Float,
        targetHeight: Float,
    ): IslandCenterCropWindow? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0f || targetHeight <= 0f) {
            return null
        }
        val scale = maxOf(targetWidth / sourceWidth, targetHeight / sourceHeight)
        val visibleWidth = targetWidth / scale
        val visibleHeight = targetHeight / scale
        val left = (sourceWidth - visibleWidth) / 2f
        val top = (sourceHeight - visibleHeight) / 2f
        return IslandCenterCropWindow(
            left = left,
            top = top,
            right = left + visibleWidth,
            bottom = top + visibleHeight,
        )
    }

    fun selectGeometry(
        candidates: List<IslandGradientGeometryCandidate>,
        moduleWidth: Int,
        moduleHeight: Int,
        iconCenterX: Float,
        iconCenterY: Float,
        rootLeft: Float,
        rootRight: Float,
        isSmallIsland: Boolean,
        tolerance: Int,
    ): IslandGradientGeometryCandidate? {
        val valid = candidates.filter { geometry ->
            val right = geometry.left + geometry.width
            val bottom = geometry.top + geometry.height
            geometry.width > 0 &&
                kotlin.math.abs(geometry.height - moduleHeight) <= tolerance &&
                geometry.left >= rootLeft &&
                right <= rootRight &&
                iconCenterX >= geometry.left - tolerance &&
                iconCenterX <= right + tolerance &&
                iconCenterY >= geometry.top - tolerance &&
                iconCenterY <= bottom + tolerance &&
                if (isSmallIsland) {
                    kotlin.math.abs(geometry.width - moduleWidth) <= tolerance
                } else {
                    geometry.width > moduleHeight * 1.5f
                }
        }
        return if (isSmallIsland) {
            valid.minByOrNull { kotlin.math.abs(it.width - moduleWidth) }
        } else {
            valid.maxByOrNull { it.width }
        }
    }

    fun fromActualEdges(
        actualLeft: Int,
        actualTop: Int,
        actualRight: Int,
        actualBottom: Int,
    ): IslandGradientGeometryCandidate {
        return IslandGradientGeometryCandidate(
            left = actualLeft,
            top = actualTop,
            width = (actualRight - actualLeft).coerceAtLeast(0),
            height = (actualBottom - actualTop).coerceAtLeast(0),
        )
    }
}

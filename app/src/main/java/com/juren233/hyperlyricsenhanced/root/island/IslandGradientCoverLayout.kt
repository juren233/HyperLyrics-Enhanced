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
    const val FIND_AND_INIT_VIEWS_METHOD = "findAndInitViews"

    fun isSmallIslandState(className: String?): Boolean {
        return className == SMALL_ISLAND_STATE_CLASS
    }
}

internal object IslandGradientCoverLayout {
    private const val GRADIENT_BAND_DP = 10f
    private const val GRADIENT_BAND_MAX_FRACTION = 0.32f

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

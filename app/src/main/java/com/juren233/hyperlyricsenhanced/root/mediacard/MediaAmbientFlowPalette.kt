package com.juren233.hyperlyricsenhanced.root.mediacard

import android.graphics.Bitmap
import com.juren233.hyperlyricsenhanced.common.color.ColorExtractor

internal data class MediaAmbientFlowPalette(
    val mainColor: Int,
    val colors: IntArray
)

internal object MediaAmbientFlowPaletteExtractor {
    fun extractCoverMainColor(bitmap: Bitmap): Int? =
        ColorExtractor.extractThemePalette(bitmap, 3).onBlackBackground.firstOrNull()
}

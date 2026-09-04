package com.juren233.hyperlyricsenhanced.root.mediacard

import android.graphics.Bitmap
import com.juren233.hyperlyricsenhanced.common.color.ColorExtractor

internal data class MediaAmbientFlowPalette(
    val mainColor: Int,
    val colors: IntArray
)

internal object MediaAmbientFlowPaletteExtractor {
    // 必须与超级岛字体颜色「封面色」基准（CoverColorHelper 非渐变路径）保持同一调用形态：
    // maxColors=1 的 onBlackBackground 首色。改大 maxColors 会改变 k-means 聚类数，
    // 导致流光与字体封面色对同一封面选出不同主色。
    fun extractCoverMainColor(bitmap: Bitmap): Int? =
        ColorExtractor.extractThemePalette(bitmap, 1).onBlackBackground.firstOrNull()
}

package com.juren233.hyperlyricsenhanced.root.utils

import android.graphics.Bitmap
import com.juren233.hyperlyricsenhanced.common.color.ColorExtractor

object CoverColorHelper {

    private data class ArtworkSignature(
        val width: Int,
        val height: Int,
        val sampledPixelHash: Int
    )

    private data class CacheEntry(
        val useGradient: Boolean,
        val artworkSignature: ArtworkSignature,
        val colors: Pair<IntArray, IntArray>
    )

    private var activeMediaKey: String? = null
    private var cachedKey: String? = null
    private var cachedArtworkSignature: ArtworkSignature? = null
    private var cachedLightColors: IntArray? = null
    private var cachedDarkColors: IntArray? = null
    private val keyedCache = LinkedHashMap<String, CacheEntry>()

    fun updateMediaSession(
        packageName: String,
        title: String,
        artist: String,
        album: String
    ): String {
        val mediaKey = listOf(packageName, title, artist, album)
            .joinToString("\u001F") { it.trim() }
        if (activeMediaKey != mediaKey) {
            activeMediaKey = mediaKey
            cachedKey = null
            cachedArtworkSignature = null
            cachedLightColors = null
            cachedDarkColors = null
        }
        return mediaKey
    }

    fun currentMediaKey(): String? = activeMediaKey

    fun artworkContentKey(bitmap: Bitmap): Int = bitmap.artworkSignature().hashCode()

    fun extractColors(bitmap: Bitmap, useGradient: Boolean, songKey: String? = null): Pair<IntArray, IntArray> {
        val key = buildKey(useGradient, songKey)
        val artworkSignature = bitmap.artworkSignature()

        if (key == cachedKey &&
            artworkSignature == cachedArtworkSignature &&
            cachedLightColors != null &&
            cachedDarkColors != null
        ) {
            return Pair(cachedLightColors!!, cachedDarkColors!!)
        }
        keyedCache[key]
            ?.takeIf { it.artworkSignature == artworkSignature }
            ?.colors
            ?.let { colors ->
                cachedKey = key
                cachedArtworkSignature = artworkSignature
                cachedLightColors = colors.first
                cachedDarkColors = colors.second
                return colors
            }

        // MediaSession and Lyricon can identify the same artwork with slightly different
        // metadata keys. Reuse the artwork result before running the randomized extractor again.
        keyedCache.values
            .firstOrNull { it.useGradient == useGradient && it.artworkSignature == artworkSignature }
            ?.colors
            ?.let { colors ->
                cachedKey = key
                cachedArtworkSignature = artworkSignature
                cachedLightColors = colors.first
                cachedDarkColors = colors.second
                keyedCache[key] = CacheEntry(useGradient, artworkSignature, colors)
                trimCache()
                return colors
            }

        val result = ColorExtractor.extractThemePalette(bitmap, if (useGradient) 4 else 1)
        val lightColors = result.onWhiteBackground.toIntArray()
        val darkColors = result.onBlackBackground.toIntArray()

        cachedKey = key
        cachedArtworkSignature = artworkSignature
        cachedLightColors = lightColors
        cachedDarkColors = darkColors
        val pair = Pair(lightColors, darkColors)
        keyedCache[key] = CacheEntry(useGradient, artworkSignature, pair)
        trimCache()
        return pair
    }

    fun getCachedColors(): Pair<IntArray, IntArray>? {
        val light = cachedLightColors ?: return null
        val dark = cachedDarkColors ?: return null
        return Pair(light, dark)
    }

    fun getCachedColors(useGradient: Boolean, songKey: String? = null): Pair<IntArray, IntArray>? {
        return keyedCache[buildKey(useGradient, songKey)]?.colors
    }

    fun clearCache() {
        activeMediaKey = null
        cachedKey = null
        cachedArtworkSignature = null
        cachedLightColors = null
        cachedDarkColors = null
        keyedCache.clear()
    }

    private fun buildKey(useGradient: Boolean, songKey: String?): String {
        return "${songKey.orEmpty()}_$useGradient"
    }

    private fun Bitmap.artworkSignature(): ArtworkSignature {
        val sampledPixelHash = runCatching {
            sampledArtworkHash(width, height, ::getPixel)
        }.getOrElse {
            val softwareCopy = copy(Bitmap.Config.ARGB_8888, false)
            try {
                sampledArtworkHash(softwareCopy.width, softwareCopy.height, softwareCopy::getPixel)
            } finally {
                softwareCopy.recycle()
            }
        }
        return ArtworkSignature(
            width = width,
            height = height,
            sampledPixelHash = sampledPixelHash
        )
    }

    private fun trimCache() {
        while (keyedCache.size > 8) {
            val firstKey = keyedCache.keys.firstOrNull() ?: return
            keyedCache.remove(firstKey)
        }
    }

    internal fun sampledArtworkHash(
        width: Int,
        height: Int,
        pixelAt: (x: Int, y: Int) -> Int
    ): Int {
        if (width <= 0 || height <= 0) return 0
        val columns = minOf(width, 8)
        val rows = minOf(height, 8)
        var hash = 17
        for (row in 0 until rows) {
            val y = if (rows == 1) 0 else row * (height - 1) / (rows - 1)
            for (column in 0 until columns) {
                val x = if (columns == 1) 0 else column * (width - 1) / (columns - 1)
                hash = 31 * hash + pixelAt(x, y)
            }
        }
        return hash
    }
}

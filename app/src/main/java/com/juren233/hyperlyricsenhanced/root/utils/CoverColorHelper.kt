package com.juren233.hyperlyricsenhanced.root.utils

import android.graphics.Bitmap
import com.juren233.hyperlyricsenhanced.common.color.ColorExtractor

object CoverColorHelper {

    internal enum class PaletteSource {
        ARTWORK_ACTIVE_CACHE,
        ARTWORK_KEYED_CACHE,
        ARTWORK_SIGNATURE_CACHE,
        ARTWORK_EXTRACTED,
        ARTWORK_SAMPLED_FALLBACK,
        KEYED_CACHE,
        ACTIVE_CACHE
    }

    internal data class ResolvedPalette(
        val colors: Pair<IntArray, IntArray>,
        val source: PaletteSource,
        val requestedKey: String,
        val resolvedKey: String?,
        val artworkSignature: Int?
    )

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
        album: String,
        stableTitle: String? = null,
        stableArtist: String? = null,
        diagnosticSource: String = "unspecified"
    ): String {
        val mediaKey = buildMediaKey(
            packageName = packageName,
            title = title,
            artist = artist,
            album = album,
            stableTitle = stableTitle,
            stableArtist = stableArtist
        )
        if (activeMediaKey != mediaKey) {
            val previousMediaKey = activeMediaKey
            val previousCachedKey = cachedKey
            val hadActivePalette = cachedLightColors != null && cachedDarkColors != null
            activeMediaKey = mediaKey
            cachedKey = null
            cachedArtworkSignature = null
            cachedLightColors = null
            cachedDarkColors = null
            CoverColorDiagnostics.logMediaKeyChange(
                source = diagnosticSource,
                previousMediaKey = previousMediaKey,
                mediaKey = mediaKey,
                previousCachedKey = previousCachedKey,
                hadActivePalette = hadActivePalette,
                keyedCacheSize = keyedCache.size
            )
        }
        return mediaKey
    }

    /**
     * Build the cache identity from track metadata, preferring the source-owned identity
     * when MediaSession temporarily publishes the current lyric line as its title.
     */
    internal fun buildMediaKey(
        packageName: String,
        title: String,
        artist: String,
        album: String,
        stableTitle: String? = null,
        stableArtist: String? = null
    ): String {
        val keyTitle = stableTitle?.trim()?.takeIf { it.isNotEmpty() } ?: title
        val keyArtist = stableArtist?.trim()?.takeIf { it.isNotEmpty() } ?: artist
        return listOf(packageName, keyTitle, keyArtist, album)
            .joinToString("\u001F") { it.trim() }
    }

    fun currentMediaKey(): String? = activeMediaKey

    fun artworkContentKey(bitmap: Bitmap): Int = bitmap.artworkSignature().hashCode()

    fun fallbackArtworkColor(bitmap: Bitmap): Int? = runCatching {
        sampledArtworkColor(bitmap.width, bitmap.height, bitmap::getPixel)
    }.getOrElse {
        val softwareCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        try {
            sampledArtworkColor(softwareCopy.width, softwareCopy.height, softwareCopy::getPixel)
        } finally {
            softwareCopy.recycle()
        }
    }

    fun extractColors(bitmap: Bitmap, useGradient: Boolean, songKey: String? = null): Pair<IntArray, IntArray> {
        return resolveArtworkColors(bitmap, useGradient, songKey).colors
    }

    internal fun resolveColors(
        bitmap: Bitmap?,
        useGradient: Boolean,
        songKey: String? = null
    ): ResolvedPalette? {
        if (bitmap != null) return resolveArtworkColors(bitmap, useGradient, songKey)

        val key = buildKey(useGradient, songKey)
        keyedCache[key]?.let { entry ->
            return ResolvedPalette(
                colors = entry.colors,
                source = PaletteSource.KEYED_CACHE,
                requestedKey = key,
                resolvedKey = key,
                artworkSignature = entry.artworkSignature.hashCode()
            )
        }

        val light = cachedLightColors ?: return null
        val dark = cachedDarkColors ?: return null
        return ResolvedPalette(
            colors = Pair(light, dark),
            source = PaletteSource.ACTIVE_CACHE,
            requestedKey = key,
            resolvedKey = cachedKey,
            artworkSignature = cachedArtworkSignature?.hashCode()
        )
    }

    private fun resolveArtworkColors(
        bitmap: Bitmap,
        useGradient: Boolean,
        songKey: String?
    ): ResolvedPalette {
        val key = buildKey(useGradient, songKey)
        val artworkSignature = bitmap.artworkSignature()

        if (key == cachedKey &&
            artworkSignature == cachedArtworkSignature &&
            cachedLightColors != null &&
            cachedDarkColors != null
        ) {
            return ResolvedPalette(
                colors = Pair(cachedLightColors!!, cachedDarkColors!!),
                source = PaletteSource.ARTWORK_ACTIVE_CACHE,
                requestedKey = key,
                resolvedKey = cachedKey,
                artworkSignature = artworkSignature.hashCode()
            )
        }
        keyedCache[key]
            ?.takeIf { it.artworkSignature == artworkSignature }
            ?.let { entry ->
                cachedKey = key
                cachedArtworkSignature = artworkSignature
                cachedLightColors = entry.colors.first
                cachedDarkColors = entry.colors.second
                return ResolvedPalette(
                    colors = entry.colors,
                    source = PaletteSource.ARTWORK_KEYED_CACHE,
                    requestedKey = key,
                    resolvedKey = key,
                    artworkSignature = artworkSignature.hashCode()
                )
            }

        // MediaSession and Lyricon can identify the same artwork with slightly different
        // metadata keys. Reuse the artwork result before running the randomized extractor again.
        keyedCache.entries
            .firstOrNull { (_, entry) ->
                entry.useGradient == useGradient && entry.artworkSignature == artworkSignature
            }
            ?.let { (_, entry) ->
                cachedKey = key
                cachedArtworkSignature = artworkSignature
                cachedLightColors = entry.colors.first
                cachedDarkColors = entry.colors.second
                keyedCache[key] = CacheEntry(useGradient, artworkSignature, entry.colors)
                trimCache()
                return ResolvedPalette(
                    colors = entry.colors,
                    source = PaletteSource.ARTWORK_SIGNATURE_CACHE,
                    requestedKey = key,
                    resolvedKey = key,
                    artworkSignature = artworkSignature.hashCode()
                )
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
        return ResolvedPalette(
            colors = pair,
            source = PaletteSource.ARTWORK_EXTRACTED,
            requestedKey = key,
            resolvedKey = key,
            artworkSignature = artworkSignature.hashCode()
        )
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

    internal fun sampledArtworkColor(
        width: Int,
        height: Int,
        pixelAt: (x: Int, y: Int) -> Int
    ): Int? {
        if (width <= 0 || height <= 0) return null
        val columns = minOf(width, 8)
        val rows = minOf(height, 8)
        var totalAlpha = 0L
        var red = 0L
        var green = 0L
        var blue = 0L
        for (row in 0 until rows) {
            val y = if (rows == 1) 0 else row * (height - 1) / (rows - 1)
            for (column in 0 until columns) {
                val x = if (columns == 1) 0 else column * (width - 1) / (columns - 1)
                val color = pixelAt(x, y)
                val alpha = (color ushr 24) and 0xFF
                if (alpha == 0) continue
                totalAlpha += alpha
                red += ((color ushr 16) and 0xFF) * alpha.toLong()
                green += ((color ushr 8) and 0xFF) * alpha.toLong()
                blue += (color and 0xFF) * alpha.toLong()
            }
        }
        if (totalAlpha == 0L) return null
        return (0xFF shl 24) or
            ((red / totalAlpha).toInt() shl 16) or
            ((green / totalAlpha).toInt() shl 8) or
            (blue / totalAlpha).toInt()
    }
}

package com.juren233.hyperlyricsenhanced.root.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverColorHelperTest {

    @Test
    fun `same artwork pixels produce a stable signature across bitmap instances`() {
        val pixels = IntArray(16 * 16) { index -> index * 37 }

        val first = CoverColorHelper.sampledArtworkHash(16, 16) { x, y -> pixels[y * 16 + x] }
        val second = CoverColorHelper.sampledArtworkHash(16, 16) { x, y -> pixels[y * 16 + x] }

        assertEquals(first, second)
    }

    @Test
    fun `different artwork pixels produce a different signature`() {
        val first = CoverColorHelper.sampledArtworkHash(16, 16) { x, y -> x + y }
        val second = CoverColorHelper.sampledArtworkHash(16, 16) { x, y -> x + y + 1 }

        assertNotEquals(first, second)
    }

    @Test
    fun `stable lyric identity keeps the same key when media title is a lyric line`() {
        val first = CoverColorHelper.buildMediaKey(
            packageName = "com.salt.music",
            title = "First lyric line",
            artist = "imase - NIGHT DANCER",
            album = "NIGHT DANCER",
            stableTitle = "NIGHT DANCER",
            stableArtist = "imase"
        )
        val second = CoverColorHelper.buildMediaKey(
            packageName = "com.salt.music",
            title = "Second lyric line",
            artist = "imase - NIGHT DANCER",
            album = "NIGHT DANCER",
            stableTitle = "NIGHT DANCER",
            stableArtist = "imase"
        )

        assertEquals(first, second)
    }

    @Test
    fun `opaque white artwork remains white instead of becoming a default-color sentinel`() {
        assertEquals(
            0xFFFFFFFF.toInt(),
            CoverColorHelper.sampledArtworkColor(8, 8) { _, _ -> 0xFFFFFFFF.toInt() }
        )
    }

    @Test
    fun `fully transparent artwork has no usable fallback color`() {
        assertNull(
            CoverColorHelper.sampledArtworkColor(8, 8) { _, _ -> 0x00FFFFFF }
        )
    }

    @Test
    fun `gradient uses the dominant color and one representative endpoint`() {
        val colors = intArrayOf(
            0xFFFF0000.toInt(),
            0xFFFF0101.toInt(),
            0xFF0000FF.toInt(),
            0xFFFFFF00.toInt(),
        )

        val indices = CoverColorHelper.gradientEndpointIndices(colors)

        assertEquals(listOf(0, 2), indices.toList())
    }

    @Test
    fun `gradient endpoint selection removes repeated colors`() {
        val colors = intArrayOf(
            0xFFFF0000.toInt(),
            0xFFFF0000.toInt(),
            0xFF0000FF.toInt(),
        )

        val indices = CoverColorHelper.gradientEndpointIndices(colors)

        assertEquals(listOf(0, 2), indices.toList())
    }

    @Test
    fun `single extracted color remains a single endpoint`() {
        val colors = intArrayOf(
            0xFF336699.toInt(),
            0xFF336699.toInt(),
        )

        val indices = CoverColorHelper.gradientEndpointIndices(colors)

        assertEquals(listOf(0), indices.toList())
    }

}

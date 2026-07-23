package com.juren233.hyperlyricsenhanced.root.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}

package com.juren233.hyperlyricsenhanced.root.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertArrayEquals

class CoverColorHelperTest {

    @Before fun resetBefore() = CoverColorHelper.clearCache()
    @After fun resetAfter() = CoverColorHelper.clearCache()

    // Seed private state to reproduce a completed extractor without Android Bitmap stubs.
    // Production cache lookup is invoked unchanged; no test-only production setters.
    private fun seedActive(key: String, colors: IntArray) {
        for ((name, value) in listOf(
            "cachedKey" to key, "cachedLightColors" to colors, "cachedDarkColors" to colors,
        )) {
            CoverColorHelper::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(CoverColorHelper, value)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun seedKeyed(key: String, gradient: Boolean, colors: IntArray) {
        val nested = CoverColorHelper::class.java.declaredClasses
        val signature = nested.single { it.simpleName == "ArtworkSignature" }
            .declaredConstructors.single().apply { isAccessible = true }.newInstance(1, 1, 42)
        val entry = nested.single { it.simpleName == "CacheEntry" }
            .declaredConstructors.single().apply { isAccessible = true }
            .newInstance(gradient, signature, Pair(colors, colors))
        val cache = CoverColorHelper::class.java.getDeclaredField("keyedCache").apply {
            isAccessible = true
        }.get(CoverColorHelper) as MutableMap<String, Any>
        cache[key] = entry
    }

    @Test fun `single keyed cache survives gradient progress becoming active`() {
        val single = intArrayOf(0xFFFF0000.toInt())
        seedKeyed("song_false", false, single)
        seedActive("song_true", intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()))
        val result = CoverColorHelper.resolveTextColors(null, false, "song")!!
        assertEquals(CoverColorHelper.PaletteSource.KEYED_CACHE, result.source)
        assertArrayEquals(single, result.colors.second)
        assertEquals("song_false", result.resolvedKey)
    }

    @Test fun `gradient keyed cache stays independent from active single text color`() {
        val gradient = intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt())
        seedKeyed("song_true", true, gradient)
        seedActive("song_false", intArrayOf(0xFFFF0000.toInt()))
        val result = CoverColorHelper.resolveTextColors(null, true, "song")!!
        assertEquals(CoverColorHelper.PaletteSource.KEYED_CACHE, result.source)
        assertEquals(3, result.colors.second.size)
        assertEquals("song_true", result.resolvedKey)
    }

    @Test fun `single text request cannot borrow active gradient progress palette`() {
        seedActive("song_true", intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()))
        assertNull(CoverColorHelper.resolveTextColors(null, false, "song"))
    }

    @Test fun `gradient request cannot borrow active single text palette`() {
        seedActive("song_false", intArrayOf(0xFFFF0000.toInt()))
        assertNull(CoverColorHelper.resolveTextColors(null, true, "song"))
    }

    @Test fun `missing artwork on next track cannot borrow previous track colors`() {
        seedActive("old_false", intArrayOf(0xFFFF0000.toInt()))
        assertNull(CoverColorHelper.resolveTextColors(null, false, "new"))
        assertNull(CoverColorHelper.resolveTextColors(null, false, null))
    }

    @Test fun `matching active palette survives missing artwork and clears normally`() {
        val colors = intArrayOf(0xFFFF0000.toInt())
        seedActive("song_false", colors)
        val result = CoverColorHelper.resolveTextColors(null, false, "song")!!
        assertArrayEquals(colors, result.colors.second)
        assertEquals(result.requestedKey, result.resolvedKey)
        CoverColorHelper.clearCache()
        assertNull(CoverColorHelper.resolveTextColors(null, false, "song"))
    }

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

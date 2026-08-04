package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class AppleOriginalMetadataCacheTest {
    @Test
    fun `keeps an independent twenty thousand entry cache`() {
        assertEquals(20_000, AppleOriginalMetadataCache.MAX_ENTRIES)
    }
}

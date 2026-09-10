/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4 缓存所有者回归：LRU 上限、预热互斥门与持久化开关的默认语义。 */
class AppleInternalCatalogCachesTest {

    private fun alias(title: String) = Alias(title, "artist", "ja-JP")

    @Test
    fun `original song cache evicts least recently used entries beyond capacity`() {
        val caches = AppleInternalCatalogCaches()
        val total = CACHE_SIZE + 1
        synchronized(caches.originalSongCache) {
            repeat(total) { index ->
                caches.originalSongCache[index.toString()] = alias("t$index")
            }
        }
        synchronized(caches.originalSongCache) {
            assertEquals(CACHE_SIZE, caches.originalSongCache.size)
            assertNull(caches.originalSongCache["0"])
            assertEquals(alias("t${total - 1}"), caches.originalSongCache["${total - 1}"])
        }
    }

    @Test
    fun `warm gate arms once per selection and reset re-arms after disable and enable`() {
        val caches = AppleInternalCatalogCaches()

        assertTrue(caches.beginLocalizedCacheWarm(1))
        assertFalse(caches.beginLocalizedCacheWarm(1))
        assertTrue(caches.beginLocalizedCacheWarm(2))

        caches.abandonLocalizedCacheWarm(2)
        assertTrue(caches.beginLocalizedCacheWarm(2))
        caches.completeLocalizedCacheWarm(2)
        assertFalse(caches.beginLocalizedCacheWarm(2))

        assertFalse(caches.beginLocalizedCacheWarm(1))
        caches.completeLocalizedCacheWarm(1)
        assertFalse(caches.beginLocalizedCacheWarm(1))

        caches.resetLocalizedCacheWarm(2)
        assertTrue(caches.beginLocalizedCacheWarm(2))
    }

    @Test
    fun `persistence switch defaults to enabled and can be toggled`() {
        val caches = AppleInternalCatalogCaches()
        assertTrue(caches.persistentLocalizedCacheEnabled)
        caches.persistentLocalizedCacheEnabled = false
        assertFalse(caches.persistentLocalizedCacheEnabled)
        caches.persistentLocalizedCacheEnabled = true
        assertTrue(caches.persistentLocalizedCacheEnabled)
    }

    @Test
    fun `catalog identity cache keeps locale independent facts until replaced`() {
        val caches = AppleInternalCatalogCaches()
        val identity = CatalogIdentity(
            isrc = "JPO1234567",
            fallbackAliases = listOf(alias("タイトル")),
            genres = listOf("J-Pop"),
            artistIds = listOf("1"),
        )
        assertNull(caches.catalogIdentityCache["100"])
        caches.catalogIdentityCache["100"] = identity
        assertEquals(identity, caches.catalogIdentityCache["100"])
        caches.catalogIdentityCache.remove("100")
        assertNull(caches.catalogIdentityCache["100"])
    }
}

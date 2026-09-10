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

/**
 * P4/R4 缓存所有者回归：LRU 上限与访问序、预热互斥门、持久化开关默认语义、
 * 身份缓存替换。R4 起全部经具名入口与不可变快照，不再触碰私有 map。
 */
class AppleInternalCatalogCachesTest {

    private fun alias(title: String) = Alias(title, "artist", "ja-JP")

    private fun reusableHit(caches: AppleInternalCatalogCaches, mediaId: String): Alias? {
        var hit: Alias? = null
        caches.withReusableOriginalSongAlias(
            mediaId = mediaId,
            canonicalize = { it },
            isReusable = { true },
            onHit = { hit = it },
        )
        return hit
    }

    @Test
    fun `original song cache evicts least recently used entries beyond capacity`() {
        val caches = AppleInternalCatalogCaches()
        val total = CACHE_SIZE + 1
        repeat(total) { index ->
            caches.putOriginalSongAlias(index.toString(), alias("t$index"))
        }
        val snapshot = caches.originalSongAliasSnapshot()
        assertEquals(CACHE_SIZE, snapshot.size)
        assertNull(snapshot["0"])
        assertEquals(alias("t${total - 1}"), snapshot["${total - 1}"])
        assertNull(reusableHit(caches, "0"))
    }

    @Test
    fun `reading an original song alias refreshes its recency`() {
        val caches = AppleInternalCatalogCaches()
        repeat(CACHE_SIZE) { index ->
            caches.putOriginalSongAlias(index.toString(), alias("t$index"))
        }
        // Access "0" so it becomes most-recently-used, then overflow the table.
        assertEquals(alias("t0"), reusableHit(caches, "0"))
        caches.putOriginalSongAlias("new", alias("tnew"))

        val snapshot = caches.originalSongAliasSnapshot()
        assertNull(snapshot["1"])
        assertEquals(alias("t0"), snapshot["0"])
    }

    @Test
    fun `a non reusable alias is evicted instead of returned`() {
        val caches = AppleInternalCatalogCaches()
        caches.putOriginalSongAlias("a", alias("t0"))
        var hit = false
        val served = caches.withReusableOriginalSongAlias(
            mediaId = "a",
            canonicalize = { it },
            isReusable = { false },
            onHit = { hit = true },
        )
        assertFalse(served)
        assertFalse(hit)
        assertNull(caches.originalSongAliasSnapshot()["a"])
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
    fun `persistence switch defaults to enabled and reports its previous value`() {
        val caches = AppleInternalCatalogCaches()
        assertTrue(caches.isPersistentCacheEnabled())
        assertTrue(caches.setPersistentCacheEnabled(false))
        assertFalse(caches.isPersistentCacheEnabled())
        assertFalse(caches.setPersistentCacheEnabled(true))
        assertTrue(caches.isPersistentCacheEnabled())
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
        assertNull(caches.catalogIdentity("100"))
        caches.putCatalogIdentity("100", identity)
        assertEquals(identity, caches.catalogIdentity("100"))
        assertEquals(listOf("J-Pop"), caches.catalogGenres("100"))
        assertEquals(listOf("1"), caches.catalogArtistIds("100"))
        caches.removeCatalogIdentity("100")
        assertNull(caches.catalogIdentity("100"))
    }

    @Test
    fun `localized artist alias merge writes only changed entries`() {
        val caches = AppleInternalCatalogCaches()
        val first = alias("first")
        assertEquals(setOf("a"), caches.mergeLocalizedArtistAliases(mapOf("a" to first)).keys)
        // Re-merging the identical value reports no change, but a new value does.
        assertTrue(caches.mergeLocalizedArtistAliases(mapOf("a" to first)).isEmpty())
        val changed = caches.mergeLocalizedArtistAliases(mapOf("a" to alias("second")))
        assertEquals(setOf("a"), changed.keys)
        assertEquals(alias("second"), caches.firstLocalizedArtistAlias(listOf("a")))
    }

    @Test
    fun `warm artist alias write keeps unconditional putAll recency order`() {
        val caches = AppleInternalCatalogCaches()
        val first = alias("first")
        val second = alias("second")

        caches.putAllLocalizedArtistAliases(mapOf("a" to first, "b" to second))
        assertEquals(listOf("a", "b"), caches.localizedArtistAliasSnapshot().keys.toList())

        // The filtered merge only touches the queried key and changes recency.
        caches.mergeLocalizedArtistAliases(mapOf("a" to first))
        assertEquals(listOf("b", "a"), caches.localizedArtistAliasSnapshot().keys.toList())

        // Warm re-inserts every entry in input order, matching the original putAll.
        caches.putAllLocalizedArtistAliases(mapOf("a" to first, "b" to second))
        assertEquals(listOf("a", "b"), caches.localizedArtistAliasSnapshot().keys.toList())
    }

    @Test
    fun `localized alias lookup scans keys in the caller order provided`() {
        val caches = AppleInternalCatalogCaches()
        caches.putLocalizedAlias("k2", alias("second"))
        assertNull(caches.firstLocalizedAlias(listOf("k1")))
        assertEquals(alias("second"), caches.firstLocalizedAlias(listOf("k1", "k2")))
        assertEquals(alias("second"), caches.localizedAlias("k2"))
        assertEquals(1, caches.localizedAliasSnapshot().size)
    }
}

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

class AppleOriginalCacheValidationTest {
    private val wrong = Alias("日文译名", "日文译名", "ja-JP")
    private val original = Alias("原名", "歌手", "zh-Hans-CN")
    private val acceptChinese: (Alias) -> Boolean = { isAcceptableOriginalAlias(it, "zh-Hans-CN") }

    @Test
    fun `wrong direct value is removed and valid alternate remains available`() {
        val cache = linkedMapOf("direct" to wrong, "alternate" to original)
        val result = validateOriginalCacheEntries(cache.keys, cache, acceptChinese)

        assertEquals(AppleOriginalMetadataCache.CacheHit("alternate", original), result.hit)
        assertEquals(listOf(AppleOriginalMetadataCache.CacheHit("direct", wrong)), result.rejected)
        assertTrue(removeOriginalAliasIfUnchanged(cache, result.rejected.single()))
        assertFalse(cache.containsKey("direct"))
        assertEquals(original, cache["alternate"])
    }

    @Test
    fun `all rejected entries produce a miss for the existing network fallback`() {
        val result = validateOriginalCacheEntries(listOf("direct"), mapOf("direct" to wrong), acceptChinese)
        assertNull(result.hit)
        assertEquals(1, result.rejected.size)
    }

    @Test
    fun `replacement arriving before rejection is not removed`() {
        val cache = mutableMapOf("direct" to wrong)
        val rejected = validateOriginalCacheEntries(cache.keys, cache, acceptChinese).rejected.single()
        cache["direct"] = original

        assertFalse(removeOriginalAliasIfUnchanged(cache, rejected))
        assertEquals(original, cache["direct"])
    }

    @Test
    fun `corrected entry is accepted on the next lookup`() {
        val cache = mutableMapOf("direct" to wrong)
        val rejected = validateOriginalCacheEntries(cache.keys, cache, acceptChinese).rejected.single()
        removeOriginalAliasIfUnchanged(cache, rejected)
        cache["direct"] = original

        val next = validateOriginalCacheEntries(cache.keys, cache, acceptChinese)
        assertEquals(original, next.hit?.alias)
        assertTrue(next.rejected.isEmpty())
    }

    @Test
    fun `valid primary retains precedence while malformed alternatives are rejected`() {
        val invalid = Alias("", "", "zh-Hans-CN")
        val cache = linkedMapOf("primary" to original, "alternate" to invalid)
        val result = validateOriginalCacheEntries(cache.keys, cache, acceptChinese)
        assertEquals("primary", result.hit?.key)
        assertEquals(listOf("alternate"), result.rejected.map { it.key })
    }

    @Test
    fun `missing cache is not a rejected match`() {
        val result = validateOriginalCacheEntries(listOf("missing"), emptyMap(), acceptChinese)
        assertNull(result.hit)
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `does not inspect or delete another item or an unrequested regional variant`() {
        val cache = linkedMapOf("current" to original, "other-song" to wrong, "jp-variant" to wrong)
        val result = validateOriginalCacheEntries(listOf("current"), cache, acceptChinese)
        assertEquals(original, result.hit?.alias)
        assertTrue(result.rejected.isEmpty())
        assertEquals(3, cache.size)
    }

    @Test
    fun `rejected record retains its exact raw locale for conditional database deletion`() {
        val legacy = Alias("舊名", "歌手", "zh-Hant-TW")
        val result = validateOriginalCacheEntries(listOf("legacy"), mapOf("legacy" to legacy)) { true }
        assertNull(result.hit)
        assertEquals("zh-Hant-TW", result.rejected.single().alias.language)

        val canonicalizable = original.copy(language = "zh-CN")
        val valid = validateOriginalCacheEntries(listOf("valid"), mapOf("valid" to canonicalizable), acceptChinese)
        assertEquals(original, valid.hit?.alias)
        assertTrue(valid.rejected.isEmpty())
    }

    @Test
    fun `unknown origin does not reject a supported original solely for being Japanese`() {
        val result = validateOriginalCacheEntries(listOf("direct"), mapOf("direct" to wrong)) { true }
        assertEquals(wrong, result.hit?.alias)
        assertTrue(result.rejected.isEmpty())
    }
}

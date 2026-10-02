/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleOriginalLanguageCacheTest {
    private val japaneseTranslation = Alias(
        title = "ザ・ウィークエンド",
        artist = "ザ・ウィークエンド",
        language = "ja-JP",
    )

    @Test
    fun `unknown artist origin does not turn a regional translation into an original`() {
        val artistId = "479756766"
        val regionalCache = mapOf(
            originalEntityCacheKey(LocalizedEntityType.ARTIST, "ja-JP", artistId) to
                japaneseTranslation,
        )
        val keys = originalEntityCacheLookupKeys(LocalizedEntityType.ARTIST, artistId)

        assertTrue(keys.none(regionalCache::containsKey))
        assertFalse(isAcceptableOriginalAlias(japaneseTranslation, "current"))
        assertFalse(isAcceptableOriginalAlias(japaneseTranslation, ""))
    }

    @Test
    fun `known original language rejects a different language even for an exact ID`() {
        assertFalse(isAcceptableOriginalAlias(japaneseTranslation, "ko-KR"))
        assertFalse(isAcceptableOriginalAlias(japaneseTranslation, "zh-Hans-CN"))
        assertEquals(
            null,
            selectExactOriginalEntityAlias(
                mediaId = "479756766",
                lookupIds = listOf("479756766"),
                resolved = mapOf("479756766" to japaneseTranslation),
                sourceLanguage = "ko-KR",
            ),
        )
    }

    @Test
    fun `wrong language direct cache cannot hide a matching alternate ID`() {
        val correct = Alias("아이유", "아이유", "ko-KR")
        val keys = originalEntityCacheLookupKeys(
            entityType = LocalizedEntityType.ARTIST,
            mediaId = "1",
            lookupIds = listOf("2"),
            languages = listOf("ko-KR"),
        )
        val cache = mapOf(
            originalDirectEntityCacheKey(LocalizedEntityType.ARTIST, "1") to japaneseTranslation,
            originalEntityCacheKey(LocalizedEntityType.ARTIST, "ko-KR", "2") to correct,
        )
        val selected = keys.firstNotNullOfOrNull { key ->
            cache[key]?.takeIf { isAcceptableOriginalAlias(it, "ko-KR") }
        }
        assertEquals(correct, selected)
    }

    @Test
    fun `validated direct original stays available before song language is known`() {
        val artistId = "18756224"
        val original = Alias("宇多田ヒカル", "宇多田ヒカル", "ja-JP")
        val cache = mapOf(
            originalDirectEntityCacheKey(LocalizedEntityType.ARTIST, artistId) to original,
        )
        val keys = originalEntityCacheLookupKeys(LocalizedEntityType.ARTIST, artistId)
        assertEquals(original, keys.firstNotNullOfOrNull(cache::get))
        assertTrue(isAcceptableOriginalAlias(original, "ja-JP"))
    }

    @Test
    fun `canonical Chinese tags match and legitimate Latin Japanese names remain accepted`() {
        assertTrue(isAcceptableOriginalAlias(Alias("晴天", "周杰伦", "zh-CN"), "zh-Hans-CN"))
        val original = Alias("HANA", "HANA", "ja-JP")
        assertTrue(isAcceptableOriginalAlias(original, "ja-JP"))
        assertEquals(
            original,
            selectExactOriginalEntityAlias("1", listOf("1"), mapOf("1" to original), "ja-JP"),
        )
        assertFalse(isAcceptableOriginalAlias(Alias("", "", "ja-JP"), "ja-JP"))
    }

    @Test
    fun `old originals and derived artist regions cannot repopulate the new cache`() {
        LocalizedEntityType.entries.forEach { type ->
            val keys = originalEntityCacheLookupKeys(type, "479756766", languages = listOf("ja-JP"))
            assertTrue(keys.all { it.startsWith("V3:") })
            assertTrue(keys.none { it.startsWith("V2:") })
        }
        assertTrue(originalSongCacheKey("1363310482").startsWith("V3:"))
        assertEquals(
            "hyperlyricsenhanced_apple_original_artist_regions_V3",
            ORIGINAL_ARTIST_REGION_PREFERENCES,
        )
    }

    @Test
    fun `ordinary rhythm and blues identity does not provide Japanese origin evidence`() {
        assertEquals(
            emptyList<String>(),
            languageTagsForOriginalMetadata(null, listOf("R&B/灵魂乐", "音乐"), "USUG11800560"),
        )
        assertEquals(
            null,
            inferredOriginalArtistLanguage(
                kind = InAppLibraryEntityKind.ARTIST,
                artist = "Abel Tesfaye",
                associatedArtistIds = listOf("479756766"),
                genres = listOf("R&B/灵魂乐", "音乐"),
            ),
        )
    }
}

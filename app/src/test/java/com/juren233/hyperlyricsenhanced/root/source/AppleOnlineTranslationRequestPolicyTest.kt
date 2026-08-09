package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleOnlineTranslationRequestPolicyTest {
    @Test
    fun `original metadata request does not block online lookup`() {
        val plan = AppleOnlineTranslationRequestPolicy.originalMetadataLookupPlan(
            shouldRequestOriginalMetadata = true
        )

        assertTrue(plan.requestOriginalMetadata)
        assertFalse(plan.waitForResult)
    }

    @Test
    fun `resolved Apple replacement becomes current matching metadata`() {
        val localized = song(
            originalTitle = "Reply",
            originalArtist = "kz, かぐや(cv.夏吉ゆうこ)",
            originalAlbum = "超かぐや姫!",
            resolved = true,
        )

        val replaced = AppleOnlineTranslationRequestPolicy.applyResolvedReplacement(
            localized,
            enabled = true,
        )!!

        assertEquals("Reply", replaced.name)
        assertEquals("kz, かぐや(cv.夏吉ゆうこ)", replaced.artist)
        assertEquals("超かぐや姫!", AppleOnlineTranslationRequestPolicy.effectiveAlbum(replaced))
    }

    @Test
    fun `original metadata arrival creates a new translation attempt`() {
        val localized = song()
        val resolved = song(
            originalTitle = "満ちてゆく",
            originalArtist = "藤井 風"
        )

        assertTrue(
            AppleOnlineTranslationRequestPolicy.originalMetadataChanged(localized, resolved)
        )
        assertNotEquals(
            AppleOnlineTranslationRequestPolicy.attemptKey(localized),
            AppleOnlineTranslationRequestPolicy.attemptKey(resolved)
        )
    }

    @Test
    fun `unchanged original metadata keeps the same translation attempt`() {
        val first = song("満ちてゆく", "藤井 風")
        val second = song("満ちてゆく", "藤井 風")

        assertFalse(
            AppleOnlineTranslationRequestPolicy.originalMetadataChanged(first, second)
        )
        assertTrue(
            AppleOnlineTranslationRequestPolicy.attemptKey(first) ==
                AppleOnlineTranslationRequestPolicy.attemptKey(second)
        )
    }

    @Test
    fun `album arrival changes the matching metadata and translation attempt`() {
        val before = song()
        val after = song(originalAlbum = "超かぐや姫!")

        assertTrue(
            AppleOnlineTranslationRequestPolicy.matchingMetadataChanged(before, after)
        )
        assertNotEquals(
            AppleOnlineTranslationRequestPolicy.attemptKey(before),
            AppleOnlineTranslationRequestPolicy.attemptKey(after)
        )
    }

    @Test
    fun `defers lookup while the replacement payload has no album`() {
        assertTrue(
            AppleOnlineTranslationRequestPolicy.shouldWaitForMatchingAlbum(
                song(),
                preferOriginalMetadata = true,
            )
        )
        assertFalse(
            AppleOnlineTranslationRequestPolicy.shouldWaitForMatchingAlbum(
                song(originalAlbum = "超かぐや姫!", resolved = true),
                preferOriginalMetadata = true,
            )
        )
    }

    private fun song(
        originalTitle: String? = null,
        originalArtist: String? = null,
        originalAlbum: String? = null,
        resolved: Boolean = false,
    ): Song = Song(
        id = "1882935962",
        name = "Michi Teyu Ku (Overflowing)",
        artist = "Fujii Kaze",
        duration = 315_000,
        metadata = if (originalTitle != null || originalArtist != null || originalAlbum != null) {
            lyricMetadataOf(
                LyricMetadataKeys.APPLE_ORIGINAL_TITLE to originalTitle,
                LyricMetadataKeys.APPLE_ORIGINAL_ARTIST to originalArtist,
                LyricMetadataKeys.APPLE_ORIGINAL_ALBUM to originalAlbum,
                LyricMetadataKeys.APPLE_ORIGINAL_METADATA_RESOLVED to resolved.toString(),
            )
        } else {
            null
        }
    )
}

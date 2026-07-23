package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleOnlineTranslationRequestPolicyTest {
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

    private fun song(
        originalTitle: String? = null,
        originalArtist: String? = null
    ): Song = Song(
        id = "1882935962",
        name = "Michi Teyu Ku (Overflowing)",
        artist = "Fujii Kaze",
        duration = 315_000,
        metadata = if (originalTitle != null || originalArtist != null) {
            lyricMetadataOf(
                LyricMetadataKeys.APPLE_ORIGINAL_TITLE to originalTitle,
                LyricMetadataKeys.APPLE_ORIGINAL_ARTIST to originalArtist
            )
        } else {
            null
        }
    )
}

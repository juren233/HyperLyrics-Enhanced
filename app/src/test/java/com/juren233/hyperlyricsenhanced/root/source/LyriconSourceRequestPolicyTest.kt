package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyriconSourceRequestPolicyTest {
    @Test
    fun `untimed online fallback requires the enabled-app toggle`() {
        assertTrue(allowsUntimedAppleOnlineFallback(appOnlineEnabled = true, untimedNative = true))
        assertFalse(allowsUntimedAppleOnlineFallback(appOnlineEnabled = false, untimedNative = true))
        assertFalse(allowsUntimedAppleOnlineFallback(appOnlineEnabled = true, untimedNative = false))
        assertFalse(allowsUntimedAppleOnlineFallback(appOnlineEnabled = false, untimedNative = false))
    }

    @Test
    fun `untimed Apple fallback skips lyrics whose timestamps all equal song end`() {
        val badLines = List(38) { index ->
            LrcLine(126_000L, "Line $index")
        }
        assertFalse(hasUsableTimedOnlineLyricsForUntimedApple(
            nativeLineCount = 38,
            durationMs = 131_000L,
            lines = badLines,
        ))
        assertFalse(hasUsableTimedOnlineLyricsForUntimedApple(
            nativeLineCount = 38,
            durationMs = 131_000L,
            lines = listOf(
                LrcLine(1_000L, "First"),
                LrcLine(2_000L, "Second"),
                LrcLine(3_000L, "Third"),
                LrcLine(4_000L, "Fourth"),
            ),
        ))
        assertTrue(hasUsableTimedOnlineLyricsForUntimedApple(
            nativeLineCount = 38,
            durationMs = 131_000L,
            lines = listOf(
                LrcLine(5_000L, "First"),
                LrcLine(25_000L, "Second"),
                LrcLine(50_000L, "Third"),
                LrcLine(100_000L, "Fourth"),
            ),
        ))
    }

    @Test
    fun `only native text without a usable interval needs timed online lyrics`() {
        val plainText = Song(
            id = "song",
            metadata = lyricMetadataOf(LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE to "apple"),
            lyrics = listOf(
                RichLyricLine(text = "First line"),
                RichLyricLine(text = "Second line"),
            ),
        )
        assertTrue(hasUntimedAppleNativeLyrics(plainText))
        // 仅 UNTIMED 标志、歌词行为空的竞态快照不得进入纯文本替换：
        // 重叠校验没有原生文本基准，必然 matched=0 拒掉正确候选（2026-09-28 真机实证）。
        assertFalse(hasUntimedAppleNativeLyrics(plainText.copy(
            lyrics = emptyList(),
            metadata = lyricMetadataOf(LyricMetadataKeys.APPLE_NATIVE_LYRICS_UNTIMED to "true"),
        )))
        assertFalse(hasUntimedAppleNativeLyrics(plainText.copy(
            lyrics = null,
            metadata = lyricMetadataOf(LyricMetadataKeys.APPLE_NATIVE_LYRICS_UNTIMED to "true"),
        )))
        assertFalse(hasUntimedAppleNativeLyrics(plainText.copy(lyrics = emptyList())))
        assertFalse(hasUntimedAppleNativeLyrics(plainText.copy(
            metadata = lyricMetadataOf(LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE to "module"),
        )))
        assertFalse(hasUntimedAppleNativeLyrics(plainText.copy(lyrics = listOf(
            RichLyricLine(begin = 0, end = 2_000, text = "Timed line"),
        ))))
        assertFalse(hasUntimedAppleNativeLyrics(plainText.copy(
            metadata = lyricMetadataOf(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT to "true"),
        )))
    }

    @Test
    fun `untimed fallback accepts only current track and generation`() {
        assertTrue(acceptsAppleOnlineLyricResult(
            generation = 5,
            currentGeneration = 5,
            sameTrack = true,
            currentNativeLyrics = true,
            currentSongHasNativeLyrics = true,
            manualSourceSwitch = false,
            untimedNativeFallback = true,
        ))
        assertFalse(acceptsAppleOnlineLyricResult(
            generation = 5,
            currentGeneration = 6,
            sameTrack = true,
            currentNativeLyrics = true,
            currentSongHasNativeLyrics = true,
            manualSourceSwitch = false,
            untimedNativeFallback = true,
        ))
        assertFalse(acceptsAppleOnlineLyricResult(
            generation = 5,
            currentGeneration = 5,
            sameTrack = false,
            currentNativeLyrics = true,
            currentSongHasNativeLyrics = true,
            manualSourceSwitch = false,
            untimedNativeFallback = true,
        ))
    }

    @Test
    fun `automatic LunaBeat match may replace confirmed Apple native lyrics`() {
        assertTrue(
            acceptsAppleOnlineLyricResult(
                generation = 4,
                currentGeneration = 4,
                sameTrack = true,
                currentNativeLyrics = true,
                currentSongHasNativeLyrics = true,
                manualSourceSwitch = false,
                automaticLunaBeatOverride = true,
            )
        )
    }

    @Test
    fun `automatic result is rejected after native lyrics become current`() {
        assertFalse(
            acceptsAppleOnlineLyricResult(
                generation = 13,
                currentGeneration = 13,
                sameTrack = true,
                currentNativeLyrics = true,
                currentSongHasNativeLyrics = true,
                manualSourceSwitch = false,
            )
        )
    }

    @Test
    fun `automatic result is rejected when native state is stale but current song has lyrics`() {
        assertFalse(
            acceptsAppleOnlineLyricResult(
                generation = 24,
                currentGeneration = 24,
                sameTrack = true,
                currentNativeLyrics = false,
                currentSongHasNativeLyrics = true,
                manualSourceSwitch = false,
            )
        )
    }

    @Test
    fun `automatic result remains accepted for a true missing lyrics song`() {
        assertTrue(
            acceptsAppleOnlineLyricResult(
                generation = 24,
                currentGeneration = 24,
                sameTrack = true,
                currentNativeLyrics = false,
                currentSongHasNativeLyrics = false,
                manualSourceSwitch = false,
            )
        )
    }

    @Test
    fun `confirmed native transition cannot stay in repeated enrichment fast path`() {
        assertFalse(
            shouldKeepRunningAppleEnrichment(
                sameTrack = true,
                authoritativeNativeTransition = true,
                hasLyrics = true,
                needsEnrichment = true,
                originalMetadataChanged = false,
                enrichmentRunning = true,
            )
        )
        assertTrue(
            shouldKeepRunningAppleEnrichment(
                sameTrack = true,
                authoritativeNativeTransition = false,
                hasLyrics = true,
                needsEnrichment = true,
                originalMetadataChanged = false,
                enrichmentRunning = true,
            )
        )
    }

    @Test
    fun `confirmed native transition clears supplement translation attempt`() {
        assertTrue(
            shouldClearAppleOnlineTranslationAttempt(
                sameTrack = true,
                authoritativeNativeTransition = true,
                needsEnrichment = true,
                originalMetadataChanged = false,
            )
        )
    }

    @Test
    fun `ordinary repeated native callback keeps translation attempt deduplicated`() {
        assertFalse(
            shouldClearAppleOnlineTranslationAttempt(
                sameTrack = true,
                authoritativeNativeTransition = false,
                needsEnrichment = true,
                originalMetadataChanged = false,
            )
        )
    }

    @Test
    fun `manual source switch is accepted for same track despite native callback`() {
        assertTrue(
            acceptsAppleOnlineLyricResult(
                generation = 13,
                currentGeneration = 13,
                sameTrack = true,
                currentNativeLyrics = true,
                currentSongHasNativeLyrics = true,
                manualSourceSwitch = true,
            )
        )
    }

    @Test
    fun `manual source switch still rejects stale generation or different track`() {
        assertFalse(
            acceptsAppleOnlineLyricResult(
                generation = 12,
                currentGeneration = 13,
                sameTrack = true,
                currentNativeLyrics = true,
                currentSongHasNativeLyrics = true,
                manualSourceSwitch = true,
            )
        )
        assertFalse(
            acceptsAppleOnlineLyricResult(
                generation = 13,
                currentGeneration = 13,
                sameTrack = false,
                currentNativeLyrics = true,
                currentSongHasNativeLyrics = true,
                manualSourceSwitch = true,
            )
        )
    }

    @Test
    fun `untimed Apple replacement requires native text overlap to pass`() {
        val overlapping = OnlineTranslationMatcher.Result(
            song = Song(),
            matchedCount = 33,
            averageMatchScore = 0.95,
        )
        assertTrue(acceptsUntimedAppleOnlineLyrics(
            meaningfulNativeLineCount = 38,
            match = overlapping,
        ))
        // 同名错歌：重叠行数不足
        assertFalse(acceptsUntimedAppleOnlineLyrics(
            meaningfulNativeLineCount = 38,
            match = overlapping.copy(matchedCount = 10),
        ))
        // 重叠行数够但相似度低
        assertFalse(acceptsUntimedAppleOnlineLyrics(
            meaningfulNativeLineCount = 38,
            match = overlapping.copy(averageMatchScore = 0.5),
        ))
        // 无可校验的原生文本
        assertFalse(acceptsUntimedAppleOnlineLyrics(
            meaningfulNativeLineCount = 0,
            match = overlapping,
        ))
    }
}

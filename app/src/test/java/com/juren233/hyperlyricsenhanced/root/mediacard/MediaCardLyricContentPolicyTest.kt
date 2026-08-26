/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCardLyricContentPolicyTest {
    @Test
    fun `translation fallback and swapped order are applied to media card rows`() {
        val config = defaultConfig().copy(
            translationDisplayMode =
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION,
            translationFallback = true,
            swapTranslation = true,
        )
        val group = MediaCardLyricContentPolicy.lyricGroup(
            line = RichLyricLine(
                text = "Original",
                translation = "Translation",
                roma = null,
            ),
            fallbackMain = null,
            config = config,
            songHasDuet = false,
            blurDistance = 0,
        )

        assertEquals(
            listOf(MediaCardLyricTextRole.TRANSLATION, MediaCardLyricTextRole.MAIN),
            group.rows.map { it.role },
        )
        assertEquals(listOf("Translation", "Original"), group.rows.map { it.text })
    }

    @Test
    fun `duet right alignment and group vocal centering are preserved`() {
        val duetConfig = defaultConfig().copy(duetLyrics = true)
        val rightGroup = MediaCardLyricContentPolicy.lyricGroup(
            line = RichLyricLine(text = "Right", isAlignedRight = true),
            fallbackMain = null,
            config = duetConfig,
            songHasDuet = true,
            blurDistance = 0,
        )
        assertEquals(MediaCardLyricAlignment.RIGHT, rightGroup.rows.first().alignment)

        val centeredGroup = MediaCardLyricContentPolicy.lyricGroup(
            line = RichLyricLine(
                text = "Together",
                isAlignedRight = true,
                metadata = lyricMetadataOf(LyricMetadataKeys.GROUP_VOCALS to "true"),
            ),
            fallbackMain = null,
            config = duetConfig.copy(centerGroupVocals = true),
            songHasDuet = true,
            blurDistance = 0,
        )
        assertEquals(MediaCardLyricAlignment.CENTER, centeredGroup.rows.first().alignment)
    }

    @Test
    fun `non duet songs can be centered without changing duet songs`() {
        val config = defaultConfig().copy(
            duetLyrics = true,
            centerNonDuetSong = true,
        )
        val group = MediaCardLyricContentPolicy.lyricGroup(
            line = RichLyricLine(text = "Solo", isAlignedRight = true),
            fallbackMain = null,
            config = config,
            songHasDuet = false,
            blurDistance = 0,
        )
        assertEquals(MediaCardLyricAlignment.CENTER, group.rows.first().alignment)
    }

    @Test
    fun `next song preview uses configured position and end of song policy`() {
        assertFalse(
            MediaCardLyricContentPolicy.shouldShowNextSongPreview(
                enabled = true,
                positionMs = 9_000L,
                durationMs = 20_000L,
                hasActualLyrics = true,
                lastLyricStartMs = 10_000L,
            )
        )
        assertTrue(
            MediaCardLyricContentPolicy.shouldShowNextSongPreview(
                enabled = true,
                positionMs = 10_000L,
                durationMs = 20_000L,
                hasActualLyrics = true,
                lastLyricStartMs = 10_000L,
            )
        )
        val preview = MediaCardLyricContentPolicy.previewGroup(
            text = MediaCardLyricContentPolicy.formatNextSongPreview("Next", "Artist"),
            position = RootConstants.MEDIA_CARD_LYRIC_NEXT_SONG_PREVIEW_POSITION_RIGHT,
        )
        assertEquals("下一首：Next-Artist", preview.rows.single().text)
        assertEquals(MediaCardLyricAlignment.RIGHT, preview.rows.single().alignment)
    }

    @Test
    fun `media card preferences use independent keys and normalize blur range`() {
        val prefs = TestSharedPreferences(
            mapOf(
                RootConstants.KEY_HOOK_ENABLE_MEDIA_CARD_LYRICS to true,
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_MAIN_TEXT_SIZE to 23,
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_BLUR_EFFECT to
                    RootConstants.MEDIA_CARD_LYRIC_BLUR_EFFECT_NATIVE,
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_MIN_RADIUS_DP to 9f,
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_MAX_RADIUS_DP to 4f,
                RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_BLUR_EFFECT to
                    RootConstants.APPLE_MUSIC_LYRICS_BLUR_EFFECT_ADVANCED_MATERIAL,
            )
        )
        val config = MediaCardLyricPreferences.read(prefs)

        assertTrue(config.enabled)
        assertEquals(23, config.mainTextSize)
        assertEquals(RootConstants.MEDIA_CARD_LYRIC_BLUR_EFFECT_NATIVE, config.blurMode)
        assertEquals(4f, config.blurMinRadius)
        assertEquals(9f, config.blurMaxRadius)
        assertTrue(
            MediaCardLyricPreferences.contains(
                RootConstants.KEY_HOOK_MEDIA_CARD_LYRIC_TRANSLATION_DISPLAY
            )
        )
        assertFalse(MediaCardLyricPreferences.contains("unrelated"))
    }

    private fun defaultConfig() = MediaCardLyricConfig(
        enabled = true,
        mainTextSize = RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_MAIN_TEXT_SIZE,
        backingTextSize = RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_BACKING_TEXT_SIZE,
        translationTextSize = RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_TRANSLATION_TEXT_SIZE,
        translationDisplayMode =
            RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_TRANSLATION_DISPLAY_MODE,
        translationFallback = RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_TRANSLATION_FALLBACK,
        swapTranslation = RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_SWAP_TRANSLATION,
        duetLyrics = RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_DUET_LYRICS,
        centerNonDuetSong = RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_CENTER_NON_DUET_SONG,
        centerGroupVocals = RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_CENTER_GROUP_VOCALS,
        nextSongPreview = RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_NEXT_SONG_PREVIEW,
        nextSongPreviewPosition =
            RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_NEXT_SONG_PREVIEW_POSITION,
        blurMode = RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_BLUR_EFFECT,
        blurMinRadius = RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_MIN_RADIUS_DP,
        blurMaxRadius = RootConstants.DEFAULT_HOOK_MEDIA_CARD_LYRIC_NATIVE_BLUR_MAX_RADIUS_DP,
    )

    private class TestSharedPreferences(
        private val values: Map<String, Any?>,
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? =
            (values[key] as? String) ?: defValue
        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = @Suppress("UNCHECKED_CAST")
            (values[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int =
            (values[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long =
            (values[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float =
            (values[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (values[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }
}

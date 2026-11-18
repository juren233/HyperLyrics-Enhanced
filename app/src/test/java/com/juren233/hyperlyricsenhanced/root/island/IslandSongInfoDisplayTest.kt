/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.root.LyricsAvailability
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「无歌词音乐期间音乐岛状态 = 歌曲信息」（issue #63）。 */
class IslandSongInfoDisplayTest {
    @After
    fun reset() {
        IslandRuntimePreferenceOverrides.clear()
        LyriconDataBridge.clearState()
        LyricsAvailability.reset()
    }

    @Test
    fun `song info applies only after all sources report no lyrics`() {
        val prefs = preferences(mapOf(
            RootConstants.KEY_HOOK_ISLAND_PURE_MUSIC_MODE to RootConstants.ISLAND_PURE_MUSIC_MODE_SONG_INFO,
        ))
        // 找词期间不切换。
        assertEquals(IslandPureMusicGate.State.INACTIVE, IslandPureMusicGate.state(prefs))
        assertFalse(IslandSlotRuntimeConfig.from(prefs).songInfoDisplay)

        LyricsAvailability.onFirstPartyReported()
        assertEquals(IslandPureMusicGate.State.SONG_INFO, IslandPureMusicGate.state(prefs))
        assertFalse(IslandPureMusicGate.isSystemCompactActive(prefs))
        assertTrue(IslandSlotRuntimeConfig.from(prefs).songInfoDisplay)

        LyricsAvailability.reset()
        assertFalse(IslandSlotRuntimeConfig.from(prefs).songInfoDisplay)
    }

    @Test
    fun `song info also applies to pure music detected by keywords`() {
        val prefs = preferences(mapOf(
            RootConstants.KEY_HOOK_ISLAND_PURE_MUSIC_MODE to RootConstants.ISLAND_PURE_MUSIC_MODE_SONG_INFO,
        ))
        LyriconDataBridge.updateSong(Song(id = "a", name = "Song", lyrics = listOf(
            RichLyricLine(begin = 0, end = 3_000, duration = 3_000, text = "纯音乐，请欣赏"),
        )))
        assertTrue(IslandSlotRuntimeConfig.from(prefs).songInfoDisplay)

        LyriconDataBridge.updateSong(Song(id = "b", name = "Song", lyrics = listOf(
            RichLyricLine(begin = 0, end = 3_000, duration = 3_000, text = "first line"),
        )))
        assertFalse(IslandSlotRuntimeConfig.from(prefs).songInfoDisplay)
    }

    @Test
    fun `system compact and keep never turn on song info`() {
        LyricsAvailability.onFirstPartyReported()
        listOf(
            RootConstants.ISLAND_PURE_MUSIC_MODE_KEEP to IslandPureMusicGate.State.INACTIVE,
            RootConstants.ISLAND_PURE_MUSIC_MODE_SYSTEM_COMPACT to IslandPureMusicGate.State.SYSTEM_COMPACT,
        ).forEach { (mode, state) ->
            val prefs = preferences(mapOf(RootConstants.KEY_HOOK_ISLAND_PURE_MUSIC_MODE to mode))
            assertEquals(state, IslandPureMusicGate.state(prefs))
            assertFalse(IslandSlotRuntimeConfig.from(prefs).songInfoDisplay)
        }
    }

    @Test
    fun `song info keeps the cover and follows the always-on rhythm switch`() {
        val base = IslandSlotRuntimeConfig.from(preferences(mapOf(
            RootConstants.KEY_HOOK_ISLAND_LEFT_ALBUM to false,
            RootConstants.KEY_HOOK_ISLAND_RIGHT_ICON to true,
            RootConstants.KEY_HOOK_ISLAND_INTERLUDE_MINIMAL_DISPLAY to true,
            RootConstants.KEY_HOOK_ISLAND_INTERLUDE_MINIMAL_RIGHT to RootConstants.ISLAND_INTERLUDE_MINIMAL_RIGHT_DOTS,
        )))
        val songInfo = base.copy(songInfoDisplay = true)
        assertTrue(songInfo.usesMinimalDisplay(interludeActive = false))
        assertTrue(songInfo.albumVisible(interludeActive = false))
        // 间奏极简的「右侧显示」不作用于歌曲信息状态。
        assertTrue(songInfo.rhythmVisible(interludeActive = true))
        assertFalse(songInfo.copy(showRhythm = false).rhythmVisible(interludeActive = false))
        assertFalse(base.albumVisible(interludeActive = false))
    }

    @Test
    fun `song info rhythm switch shows rhythm only in the song info state`() {
        fun config(mode: Int) = IslandSlotRuntimeConfig.from(preferences(mapOf(
            RootConstants.KEY_HOOK_ISLAND_RIGHT_ICON to false,
            RootConstants.KEY_HOOK_ISLAND_PURE_MUSIC_MODE to mode,
            RootConstants.KEY_HOOK_ISLAND_PURE_MUSIC_SONG_INFO_RHYTHM to true,
        )))
        val enabled = config(RootConstants.ISLAND_PURE_MUSIC_MODE_SONG_INFO)
        assertTrue(enabled.songInfoRhythm)
        assertTrue(enabled.rhythmMayShow)
        assertTrue(enabled.copy(songInfoDisplay = true).rhythmVisible(interludeActive = false))
        // 有歌词时不受该开关影响。
        assertFalse(enabled.rhythmVisible(interludeActive = false))
        // 只有选了「歌曲信息」时开关才生效。
        val otherMode = config(RootConstants.ISLAND_PURE_MUSIC_MODE_SYSTEM_COMPACT)
        assertFalse(otherMode.songInfoRhythm)
        assertFalse(otherMode.rhythmMayShow)
    }

    @Test
    fun `song info position aligns only the right slot in every lyric mode`() {
        listOf(
            RootConstants.HOOK_LYRIC_MODE_SINGLE_SIDE,
            RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND,
            RootConstants.HOOK_LYRIC_MODE_SEPARATED,
        ).forEach { lyricMode ->
            val config = IslandSlotRuntimeConfig.from(preferences(mapOf(
                RootConstants.KEY_HOOK_LYRIC_MODE to lyricMode,
            ))).copy(songInfoDisplay = true)
            assertEquals(RootConstants.ISLAND_LYRIC_POSITION_RIGHT, config.songInfoPosition)
            assertTrue(config.rightAlignLyric(isLeft = false))
            assertFalse(config.centerLyric(isLeft = false))
            assertFalse(config.rightAlignLyric(isLeft = true))

            val centered = config.copy(songInfoPosition = RootConstants.ISLAND_LYRIC_POSITION_CENTER)
            assertTrue(centered.centerLyric(isLeft = false))
            assertFalse(centered.rightAlignLyric(isLeft = false))
            assertFalse(centered.centerLyric(isLeft = true))
        }
    }

    @Test
    fun `song info position follows live overrides and invalidates the style`() {
        val prefs = preferences(emptyMap())
        val before = IslandSlotRuntimeConfig.from(prefs)
        IslandRuntimePreferenceOverrides.put(
            RootConstants.KEY_HOOK_ISLAND_PURE_MUSIC_SONG_INFO_POSITION,
            RootConstants.ISLAND_LYRIC_POSITION_CENTER,
        )
        val after = IslandSlotRuntimeConfig.from(prefs)
        assertEquals(RootConstants.ISLAND_LYRIC_POSITION_CENTER, after.songInfoPosition)
        assertTrue(before.styleSignature != after.styleSignature)
    }

    @Test
    fun `right slot is injected for song info only while it is shown`() {
        val config = IslandSlotRuntimeConfig.from(preferences(mapOf(
            RootConstants.KEY_HOOK_ISLAND_CONTENT_LEFT to 7,
            RootConstants.KEY_HOOK_ISLAND_CONTENT_RIGHT to 0,
            // 半屏下首预览也会占用右槽，这里排除它。
            RootConstants.KEY_HOOK_ISLAND_NEXT_SONG_PREVIEW_STYLE to RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_NONE,
        )))
        assertFalse(config.shouldInjectRight)
        assertTrue(config.copy(songInfoDisplay = true).shouldInjectRight)
    }

    private fun preferences(values: Map<String, Any>): SharedPreferences {
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getAll" -> values
                "contains" -> values.containsKey(args!![0])
                "getInt" -> (values[args!![0] as String] as? Int) ?: args[1]
                "getBoolean" -> (values[args!![0] as String] as? Boolean) ?: args[1]
                "getString", "getStringSet", "getFloat", "getLong" -> args!![1]
                else -> null
            }
        } as SharedPreferences
    }
}

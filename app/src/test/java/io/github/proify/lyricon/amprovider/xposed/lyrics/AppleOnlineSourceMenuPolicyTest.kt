/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.lyrics

import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceStatus
import io.github.proify.lyricon.amprovider.xposed.OnlineSourceMenuStatus
import io.github.proify.lyricon.amprovider.xposed.currentLyricsMenuSongId
import io.github.proify.lyricon.amprovider.xposed.isMissingLyricsSourceSelectable
import io.github.proify.lyricon.amprovider.xposed.missingLyricsSourceMenuLabel
import io.github.proify.lyricon.amprovider.xposed.missingLyricsSourceStatusLabel
import io.github.proify.lyricon.amprovider.xposed.sourceMenuLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppleOnlineSourceMenuPolicyTest {
    @Test
    fun `lyrics menu label uses every provider prefix`() {
        assertEquals("网易歌词", sourceMenuLabel("NE", "lyrics"))
        assertEquals("QQ歌词", sourceMenuLabel("QM", "lyrics"))
        assertEquals("酷我歌词", sourceMenuLabel("KUWO", "lyrics"))
        assertEquals("酷狗歌词", sourceMenuLabel("KUGOU", "lyrics"))
        assertEquals("LB歌词", sourceMenuLabel("LB", "lyrics"))
        assertEquals("原生歌词", sourceMenuLabel("APPLE", "lyrics"))
    }

    @Test
    fun `legacy supplement without a known provider uses neutral source label`() {
        assertEquals("歌词来源", missingLyricsSourceMenuLabel(null))
        assertEquals("酷狗歌词", missingLyricsSourceMenuLabel("KUGOU"))
    }

    @Test
    fun `switching and failure labels remain unchanged`() {
        assertEquals("切换中", sourceMenuLabel("NE", "lyrics", OnlineSourceMenuStatus.SWITCHING))
        assertEquals("切换失败", sourceMenuLabel("NE", "lyrics", OnlineSourceMenuStatus.FAILED))
    }

    @Test
    fun `current playback identity wins over a stale visible lyrics model`() {
        assertEquals(
            "1810905308",
            currentLyricsMenuSongId(
                playbackSongId = "1810905308",
                visibleLyricsSongId = "1440818674",
            ),
        )
        assertEquals(
            "1440818674",
            currentLyricsMenuSongId(
                playbackSongId = " ",
                visibleLyricsSongId = "1440818674",
            ),
        )
    }

    @Test
    fun `source dialog keeps the full Apple Music native lyrics label`() {
        assertEquals(
            "Apple Music 原生歌词",
            missingLyricsSourceStatusLabel(
                AppleMissingLyricsSourceStatus(
                    source = "APPLE",
                    searched = true,
                    found = true,
                    wordTimed = true,
                    lineCount = 1,
                )
            ),
        )
    }

    @Test
    fun `source dialog distinguishes no match from pending source information`() {
        val unsearched = missingLyricsSourceStatusLabel(
            AppleMissingLyricsSourceStatus(
                source = "KUWO",
                searched = false,
                found = false,
            )
        )
        val failed = missingLyricsSourceStatusLabel(
            AppleMissingLyricsSourceStatus(
                source = "KUWO",
                searched = true,
                found = false,
            )
        )
        assertFalse(unsearched.contains("失败"))
        assertFalse(failed.contains("失败"))
        assertEquals("正在获取来源信息", unsearched)
        assertEquals("未找到匹配歌词", failed)
    }

    @Test
    fun `source dialog renders successful retrieval detail`() {
        assertEquals(
            "逐字歌词 · 60 句",
            missingLyricsSourceStatusLabel(
                AppleMissingLyricsSourceStatus(
                    source = "KUGOU",
                    searched = true,
                    found = true,
                    wordTimed = true,
                    lineCount = 60,
                )
            )
        )
    }

    @Test
    fun `only a found unselected source is selectable`() {
        val found = AppleMissingLyricsSourceStatus("NE", searched = true, found = true)
        val missing = AppleMissingLyricsSourceStatus("QM", searched = true, found = false)
        val onDemand = AppleMissingLyricsSourceStatus("LB", searched = false, found = false)
        assertEquals(true, isMissingLyricsSourceSelectable(found, selected = false))
        assertEquals(false, isMissingLyricsSourceSelectable(found, selected = true))
        assertEquals(false, isMissingLyricsSourceSelectable(missing, selected = false))
        assertEquals(true, isMissingLyricsSourceSelectable(onDemand, selected = false))
    }
}

/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.online.source.qm.QmMetadataMatch
import com.juren233.hyperlyricsenhanced.online.source.qm.QmTrackDetail
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QqMusicCatalogSongIdTest {
    private fun detail(id: Long, name: String, singer: String) =
        QmTrackDetail(id, name, listOf(singer), "", "", 200_000L, "", 0)

    private fun track(title: String, artist: String, mediaId: String) =
        TrackIdentity("com.tencent.qqmusic", mediaId, title, artist, durationMs = 190_000L)

    /** 测试用身份规则：歌名、歌手都相等（真实规则由 OnlineLyricTargeter 提供）。 */
    private val sameSong: (TrackIdentity, QmTrackDetail) -> Boolean = { track, detail ->
        track.title == detail.name && detail.singers.contains(track.artist)
    }

    private fun resolver(
        details: Map<Long, QmTrackDetail>,
        match: QmMetadataMatch?,
        onMatch: () -> Unit = {},
    ) = QqMusicCatalogSongId(
        details = { ids -> details.filterKeys { it in ids } },
        match = { _, _, _ -> onMatch(); match },
        sameSong = sameSong,
    )

    @Test
    fun `online session id is used directly`() = runBlocking {
        var matches = 0
        val resolved = resolver(
            mapOf(556300755L to detail(556300755L, "日落", "孙燕姿")),
            match = null,
            onMatch = { matches++ },
        ).resolve("556300755", track("日落", "孙燕姿", "556300755"))
        assertEquals("556300755", resolved)
        assertEquals(0, matches)
    }

    @Test
    fun `local library id is replaced by the verified online id`() = runBlocking {
        // issue #62：1926 在线是莫文蔚《那么爱你为什么》
        val resolved = resolver(
            mapOf(
                1926L to detail(1926L, "那么爱你为什么", "莫文蔚"),
                605895210L to detail(605895210L, "Broken", "林峯"),
            ),
            QmMetadataMatch(605895210L, listOf(4907722L)),
        ).resolve("1926", track("Broken", "林峯", "1926"))
        assertEquals("605895210", resolved)
    }

    @Test
    fun `session id listed in match result is kept`() = runBlocking {
        val resolved = resolver(
            emptyMap(),
            QmMetadataMatch(733033289L, listOf(556300755L)),
        ).resolve("556300755", track("日落", "孙燕姿", "556300755"))
        assertEquals("556300755", resolved)
    }

    @Test
    fun `unverifiable local id is never used for catalogs`() = runBlocking {
        assertNull(
            resolver(mapOf(1926L to detail(1926L, "那么爱你为什么", "莫文蔚")), match = null)
                .resolve("1926", track("Broken", "林峯", "1926")),
        )
        assertNull(
            resolver(
                mapOf(1926L to detail(1926L, "那么爱你为什么", "莫文蔚"), 7L to detail(7L, "Broken Cities", "别人")),
                QmMetadataMatch(7L, emptyList()),
            ).resolve("1926", track("Broken", "林峯", "1926")),
        )
    }

    @Test
    fun `multi artist local song is verified through a single artist query`() = runBlocking {
        // 2026-11-09 真机：本地《勿听》会话 ID 47；整串歌手 24001，只传「张紫宁」命中 509884965
        val artist = "张紫宁/赵紫骅/游戏科学/8082Audio"
        val queried = mutableListOf<String>()
        val resolver = QqMusicCatalogSongId(
            details = { ids ->
                mapOf(
                    47L to QmTrackDetail(47L, "爱上你只是我的错", listOf("dMDM"), "", "", 176_000L, "", 0),
                    509884965L to QmTrackDetail(
                        509884965L, "勿听", listOf("张紫宁", "赵紫骅", "游戏科学", "8082Audio"),
                        "", "", 255_000L, "", 0,
                    ),
                ).filterKeys { it in ids }
            },
            match = { _, singer, _ ->
                queried += singer
                if (singer == "张紫宁") QmMetadataMatch(null, listOf(509884965L)) else null
            },
            sameSong = { track, detail -> track.title == detail.name && track.artist.split("/").any(detail.singers::contains) },
        )
        assertEquals("509884965", resolver.resolve("47", track("勿听", artist, "47")))
        assertEquals(listOf(artist, "张紫宁"), queried)
    }

    @Test
    fun `session id in a relaxed query result still needs identity check`() = runBlocking {
        val resolved = QqMusicCatalogSongId(
            details = { ids ->
                mapOf(
                    34L to detail(34L, "太空", "BEYOND"),
                    378883302L to detail(378883302L, "Question...? (Explicit)", "Taylor Swift"),
                ).filterKeys { it in ids }
            },
            match = { title, _, _ ->
                if (title == "Question...?") QmMetadataMatch(34L, listOf(378883302L)) else null
            },
            sameSong = sameSong,
        ).resolve("34", track("Question...? (Explicit)", "Taylor Swift", "34"))
        assertEquals("378883302", resolved)
    }

    @Test
    fun `lookup failure resolves to no catalog id and is retried later`() = runBlocking {
        var calls = 0
        val failing = QqMusicCatalogSongId(
            details = { calls++; throw java.io.IOException("network down") },
            match = { _, _, _ -> null },
            sameSong = sameSong,
        )
        val local = track("Billie Jean", "Michael Jackson", "1920")
        assertNull(failing.resolve("1920", local))
        assertNull(failing.resolve("1920", local))
        assertEquals(2, calls)
    }

    @Test
    fun `confirmed results are cached per track`() = runBlocking {
        var matches = 0
        val resolver = resolver(
            mapOf(1926L to detail(1926L, "那么爱你为什么", "莫文蔚")),
            match = null,
            onMatch = { matches++ },
        )
        val local = track("Broken", "林峯", "1926")
        assertNull(resolver.resolve("1926", local))
        assertNull(resolver.resolve("1926", local))
        assertEquals(1, matches)
    }
}

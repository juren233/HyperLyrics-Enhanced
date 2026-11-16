/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheLastUsed
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheSnapshot
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheSong
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheSongSummary
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheSourceStats
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheUse
import com.juren233.hyperlyricsenhanced.online.model.Source
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineLyricCacheStateTest {

    private fun song(
        tag: String,
        vararg sources: OnlineLyricCacheSourceStats,
        usedAt: Long = 0L,
        album: String = "",
        artist: String = "",
    ) =
        OnlineLyricCacheSongSummary(
            song = OnlineLyricCacheSong(tag = tag, title = tag, artist = artist, packageName = "pkg", album = album),
            usedAtMs = usedAt,
            sources = sources.toList(),
        )

    private fun stats(source: Source, hits: Int, misses: Int, bytes: Long) =
        OnlineLyricCacheSourceStats(source.name, hits, misses, bytes)

    private fun state(vararg songs: OnlineLyricCacheSongSummary, systemUi: List<OnlineLyricCacheSongSummary>? = null) =
        OnlineLyricCacheState(
            systemUi = systemUi?.let { OnlineLyricCacheSnapshot(it, totalBytes = 0L) },
            app = OnlineLyricCacheSnapshot(songs.toList(), totalBytes = 0L),
        )

    @Test
    fun songPageListsSourcesBySizeThenScoreAndKeepsTheMostRecentDetails() {
        val qm = OnlineLyricCacheSourceStats(Source.QM.name, 1, 0, 100, score = 90, wordTimed = false)
        val ne = OnlineLyricCacheSourceStats(Source.NE.name, 1, 0, 100, score = 97, wordTimed = true)
        val amll = OnlineLyricCacheSourceStats(Source.AMLL.name, 1, 0, 100, score = null, wordTimed = true)
        val kugou = OnlineLyricCacheSourceStats(Source.KUGOU.name, 1, 0, 500)
        val item = state(
            song("a", qm, ne, amll, kugou, usedAt = 1).copy(lastUsed = OnlineLyricCacheLastUsed(lyrics = Source.QM.name)),
            systemUi = listOf(
                song("a", qm.copy(score = 88, sizeBytes = 0), usedAt = 9)
                    .copy(lastUsed = OnlineLyricCacheLastUsed(lyrics = Source.AMLL.name, translation = Source.NE.name)),
                song("a", usedAt = 20),
            ),
        ).songs.single()
        assertEquals(
            listOf(Source.KUGOU.name, Source.NE.name, Source.QM.name, Source.AMLL.name),
            item.sourcesBySize.map { it.source },
        )
        assertEquals("the newer copy's score wins", 88, item.sources.single { it.source == Source.QM.name }.score)
        assertEquals(
            "the newest copy that showed online content",
            OnlineLyricCacheLastUsed(lyrics = Source.AMLL.name, translation = Source.NE.name),
            item.lastUsed,
        )
    }

    @Test
    fun aLastSourceWithoutLyricsIsNotMarked() {
        val item = state(
            song("a", stats(Source.QM, 1, 0, 100), stats(Source.NE, 0, 1, 10))
                .copy(lastUsed = OnlineLyricCacheLastUsed(lyrics = Source.QM.name, translation = Source.NE.name)),
        ).songs.single()
        assertEquals(OnlineLyricCacheLastUsed(lyrics = Source.QM.name), item.lastUsed)
    }

    @Test
    fun lastUsedBadgeNamesWhatTheSongTookFromTheSource() {
        val lyrics = OnlineLyricCacheUse.LYRICS
        val translation = OnlineLyricCacheUse.TRANSLATION
        val pronunciation = OnlineLyricCacheUse.PRONUNCIATION
        assertEquals(
            listOf(
                null,
                R.string.online_lyric_cache_last_lyrics,
                R.string.online_lyric_cache_last_translation,
                R.string.online_lyric_cache_last_pronunciation,
                R.string.online_lyric_cache_last_pronunciation_translation,
                R.string.online_lyric_cache_last_lyrics_translation,
                R.string.online_lyric_cache_last_lyrics_pronunciation,
                R.string.online_lyric_cache_last_used,
            ),
            listOf(
                emptySet(),
                setOf(lyrics),
                setOf(translation),
                setOf(pronunciation),
                setOf(translation, pronunciation),
                setOf(lyrics, translation),
                setOf(lyrics, pronunciation),
                setOf(lyrics, translation, pronunciation),
            ).map(CachedSongItem::lastUsedLabel),
        )
        val used = OnlineLyricCacheLastUsed(lyrics = "QM", translation = "KUWO", pronunciation = "KUWO")
        assertEquals(setOf(lyrics), used.usesOf("QM"))
        assertEquals(setOf(translation, pronunciation), used.usesOf("KUWO"))
        assertEquals(emptySet<OnlineLyricCacheUse>(), used.usesOf("NE"))
    }

    @Test
    fun missRecordsAreNotShown() {
        val state = state(
            song("a", stats(Source.AMLL, hits = 0, misses = 1, bytes = 0)),
            song("b", stats(Source.AMLL, hits = 1, misses = 0, bytes = 4_000), stats(Source.QM, 0, 2, 30)),
        )
        assertEquals(listOf("b"), state.songs.map { it.id })
        assertEquals(listOf("AMLL"), state.songs.single().sources.map { it.source })
        assertEquals(1, state.of(Source.AMLL).songs.size)
        assertEquals(0, state.of(Source.QM).songs.size)
    }

    @Test
    fun aSongInBothCopiesIsOneRow() {
        val state = state(
            song("a", stats(Source.QM, 1, 1, 100), usedAt = 5, album = "Album"),
            systemUi = listOf(
                song("a", stats(Source.QM, 1, 0, 200), stats(Source.NE, 0, 1, 10), usedAt = 9),
            ),
        )
        val item = state.songs.single()
        assertEquals(9L, item.usedAtMs)
        assertEquals("Album", item.song.album)
        assertEquals(listOf("QM"), item.sources.map { it.source })
        assertEquals(300L, item.sizeOf(Source.QM))
        assertEquals(300L, state.of(Source.QM).bytes)
    }

    @Test
    fun sourceRowsAreSortedBySizeThenSongCountThenSourceOrder() {
        val state = state(
            song("a", stats(Source.QM, 1, 0, 100), stats(Source.AMLL, 1, 0, 9_000), stats(Source.LB, 1, 0, 50)),
            song("b", stats(Source.KUGOU, 1, 0, 100), stats(Source.LB, 1, 0, 50)),
        )
        assertEquals(
            listOf(Source.AMLL, Source.LB, Source.QM, Source.KUGOU, Source.NE, Source.KUWO),
            state.bySource.map { it.source },
        )
    }

    @Test
    fun songsGroupByArtistOrAlbumAsRecordedOrderedBySizeThenCountThenName() {
        val songs = state(
            song("a", stats(Source.QM, 1, 0, 100), artist = "Jay", album = "Fantasy"),
            song("b", stats(Source.QM, 1, 0, 100), stats(Source.NE, 1, 0, 50), artist = " Jay ", album = "Fantasy"),
            song("c", stats(Source.NE, 1, 0, 300), artist = "Eason", album = ""),
            song("d", stats(Source.QM, 1, 0, 10), artist = "Adele", album = "25"),
            song("e", stats(Source.QM, 1, 0, 10), artist = "Bjork", album = "25"),
        ).songs

        val byArtist = songs.groupedBy(CachedSongGrouping.Artist, source = null)
        assertEquals(listOf("Eason", "Jay", "Adele", "Bjork"), byArtist.map { it.name })
        assertEquals(250L, byArtist.first { it.name == "Jay" }.bytes)

        val byAlbum = songs.groupedBy(CachedSongGrouping.Album, source = null)
        assertEquals(listOf("", "Fantasy", "25"), byAlbum.map { it.name })
        assertEquals(setOf("d", "e"), byAlbum.last().songs.map { it.id }.toSet())

        // On a source page a group's size is only that source's share.
        assertEquals(200L, songs.groupedBy(CachedSongGrouping.Artist, Source.QM).first { it.name == "Jay" }.bytes)
    }

    @Test
    fun playersAreChosenTogetherAndAllPlayersClearsTheChoice() {
        var chosen = emptySet<String>()
        chosen = PlayerFilter.toggle(chosen, "a")
        chosen = PlayerFilter.toggle(chosen, "b")
        assertEquals(setOf("a", "b"), chosen)
        chosen = PlayerFilter.toggle(chosen, "a")
        assertEquals(setOf("b"), chosen)
        assertEquals(emptySet<String>(), PlayerFilter.toggle(chosen, null))
    }

    @Test
    fun aLimitDropsSongsOnlyWhenACopyHoldsMoreSongsWithLyricsThanIt() {
        val state = state(
            song("a", stats(Source.QM, 1, 0, 1)),
            song("b", stats(Source.QM, 1, 0, 1)),
            song("c", stats(Source.QM, 0, 1, 0)),
            systemUi = listOf(song("a", stats(Source.QM, 1, 0, 1)), song("d", stats(Source.NE, 1, 0, 1))),
        )
        // Three merged rows, but each copy holds two songs with lyrics; the miss-only song does not count.
        assertEquals(false, state.dropsSongsAt(2))
        assertEquals(true, state.dropsSongsAt(1))
    }
}

/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.cache

import com.juren233.hyperlyricsenhanced.online.model.LyricsLine
import com.juren233.hyperlyricsenhanced.online.model.LyricsResult
import com.juren233.hyperlyricsenhanced.online.model.LyricsWord
import com.juren233.hyperlyricsenhanced.online.model.SongSearchResult
import com.juren233.hyperlyricsenhanced.online.model.Source
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Gzipped JSON for cached search responses and lyrics; an empty payload stands for "no lyrics". */
internal object OnlineLyricCacheCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeSongs(songs: List<SongSearchResult>): ByteArray =
        gzip(json.encodeToString(ListSerializer(CachedSong.serializer()), songs.map(CachedSong::of)))

    fun decodeSongs(payload: ByteArray): List<SongSearchResult> =
        json.decodeFromString(ListSerializer(CachedSong.serializer()), gunzip(payload))
            .map(CachedSong::toModel)

    fun encodeLyrics(lyrics: LyricsResult?): ByteArray =
        if (lyrics == null) ByteArray(0) else gzip(json.encodeToString(CachedLyrics.serializer(), CachedLyrics.of(lyrics)))

    fun decodeLyrics(payload: ByteArray): LyricsResult? =
        if (payload.isEmpty()) null else json.decodeFromString(CachedLyrics.serializer(), gunzip(payload)).toModel()

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        GZIPOutputStream(bytes).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }.toByteArray()

    private fun gunzip(payload: ByteArray): String =
        GZIPInputStream(payload.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)

    @Serializable
    private data class CachedSong(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val duration: Long,
        val source: Source,
        val date: String = "",
        val trackerNumber: String = "",
        val picUrl: String = "",
        val extras: Map<String, String> = emptyMap(),
    ) {
        fun toModel() = SongSearchResult(id, title, artist, album, duration, source, date, trackerNumber, picUrl, extras)

        companion object {
            fun of(song: SongSearchResult) = CachedSong(
                song.id, song.title, song.artist, song.album, song.duration, song.source,
                song.date, song.trackerNumber, song.picUrl, song.extras,
            )
        }
    }

    @Serializable
    private data class CachedWord(val start: Long, val end: Long, val text: String)

    @Serializable
    private data class CachedLine(
        val start: Long,
        val end: Long,
        val words: List<CachedWord>,
        val secondaryWords: List<CachedWord> = emptyList(),
    )

    @Serializable
    private data class CachedLyrics(
        val tags: Map<String, String>,
        val original: List<CachedLine>,
        val translated: List<CachedLine>? = null,
        val romanization: List<CachedLine>? = null,
    ) {
        fun toModel() = LyricsResult(
            tags = tags,
            original = original.map(::lineModel),
            translated = translated?.map(::lineModel),
            romanization = romanization?.map(::lineModel),
        )

        companion object {
            fun of(lyrics: LyricsResult) = CachedLyrics(
                tags = lyrics.tags,
                original = lyrics.original.map(::cachedLine),
                translated = lyrics.translated?.map(::cachedLine),
                romanization = lyrics.romanization?.map(::cachedLine),
            )
        }
    }

    private fun cachedLine(line: LyricsLine) = CachedLine(
        start = line.start,
        end = line.end,
        words = line.words.map { CachedWord(it.start, it.end, it.text) },
        secondaryWords = line.secondaryWords.map { CachedWord(it.start, it.end, it.text) },
    )

    private fun lineModel(line: CachedLine) = LyricsLine(
        start = line.start,
        end = line.end,
        words = line.words.map { LyricsWord(it.start, it.end, it.text) },
        secondaryWords = line.secondaryWords.map { LyricsWord(it.start, it.end, it.text) },
    )
}

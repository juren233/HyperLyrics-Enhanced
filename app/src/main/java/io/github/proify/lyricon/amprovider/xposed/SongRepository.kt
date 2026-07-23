/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import io.github.proify.lyricon.amprovider.xposed.parser.AppleSongParser
import io.github.proify.lyricon.amprovider.xposed.model.AppleSong
import io.github.proify.lyricon.amprovider.xposed.util.toSong
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.lyricMetadataOf

object SongRepository {

    /**
     * 根据 ID 获取歌曲
     * 策略：内存/磁盘缓存 -> 占位符
     */
    fun getSong(id: String): Song {
        // 1. 尝试从磁盘缓存读取
        val cache = DiskSongManager.load(id)
        if (cache != null) {
            return cache.applyCurrentMetadata().toSong()
        }

        // 2. 缓存未命中，从 Metadata 生成占位符（只有标题/歌手，无歌词）
        val metadata = MediaMetadataCache.getMetadataById(id)
        return Song(
            id = id,
            name = metadata?.title,
            artist = metadata?.artist,
            duration = metadata?.duration ?: 0L,
            metadata = metadata?.toLyricMetadata()
        )
    }

    /**
     * 保存解析好的歌曲到磁盘
     */
    fun saveSong(nativeSongObj: Any): Song? {
        val song = AppleSongParser.parser(nativeSongObj)
        if (song.adamId.isNullOrBlank()) {
            return null
        }
        if (BuildConfig.DEBUG) {
            ProviderLogger.debug(
                "SongRepository: Parsed Apple lyrics for ${song.adamId}, " +
                    "backgroundTextLines=${song.lyrics.count { !it.htmlBackgroundVocalsLineText.isNullOrBlank() }}, " +
                    "backgroundWordLines=${song.lyrics.count { it.backgroundWords.isNotEmpty() }}, " +
                    "translatedBackgroundLines=${song.lyrics.count { !it.htmlTranslatedBackgroundVocalsLineText.isNullOrBlank() }}"
            )
        }
        val songWithMetadata = song.applyCurrentMetadata()
        DiskSongManager.save(songWithMetadata)
        return songWithMetadata.toSong()
    }

    private fun AppleSong.applyCurrentMetadata(): AppleSong = apply {
        val metadata = adamId?.let(MediaMetadataCache::getMetadataById) ?: return@apply
        name = metadata.title
        artist = metadata.artist
        genre = metadata.genre
        originalTitle = metadata.originalTitle
        originalArtist = metadata.originalArtist
    }

    private fun MediaMetadataCache.Metadata.toLyricMetadata() = lyricMetadataOf(
        LyricMetadataKeys.APPLE_CATALOG_GENRE to genre,
        LyricMetadataKeys.APPLE_ORIGINAL_TITLE to originalTitle,
        LyricMetadataKeys.APPLE_ORIGINAL_ARTIST to originalArtist
    )
}

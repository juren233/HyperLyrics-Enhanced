/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.RemotePlayer
import kotlin.system.measureTimeMillis

object PlaybackManager {
    private var player: RemotePlayer? = null
    private var lyricRequester: LyricRequester? = null

    // 状态追踪
    private var currentSongId: String? = null

    fun init(remotePlayer: RemotePlayer, requester: LyricRequester) {
        this.player = remotePlayer
        this.lyricRequester = requester
    }

    /**
     * 当系统切歌或 Metadata 变化时调用
     */
    fun onSongChanged(newId: String?) {
        if (newId.isNullOrBlank()) {
            currentSongId = null
            setSong(null)
            ProviderLogger.debug("PlaybackManager: Song changed to null")
            return
        }

        // 避免重复处理同一首歌
        if (newId == currentSongId) return
        currentSongId = newId

        ProviderLogger.debug("PlaybackManager: Song changed to $newId")

        // 1. 立即设置歌曲（可能是完整版，也可能是占位版）
        val song = SongRepository.getSong(newId)
        setSong(song)

        // 2. 缓存缺少歌词或用户需要的翻译时重新下载。
        val lyrics = song.lyrics
        val needsLyrics = lyrics.isNullOrEmpty()
        val needsTranslation =
            !needsLyrics &&
                PreferencesMonitor.isTranslationSelected() &&
                lyrics.none { it.translation.isNullOrBlank() }
        if (needsLyrics || needsTranslation) {
            val queueId = MediaMetadataCache.getMetadataById(newId)?.queueId ?: 0L
            lyricRequester?.requestDownload(newId, queueId)
        } else {
            ProviderLogger.debug("PlaybackManager: Song $newId has complete lyrics, skipping download.")
        }
    }

    fun onCatalogMetadataResolved(id: String) {
        if (id != currentSongId) return
        val resolvedSong = SongRepository.getSong(id)
        val mergedSong = mergeCatalogMetadata(lastSong, resolvedSong)
        ProviderLogger.info(
            "PlaybackManager: Catalog metadata ready for current song $id, " +
                "resolvedLines=${resolvedSong.lyrics?.size ?: 0}, " +
                "publishedLines=${mergedSong.lyrics?.size ?: 0}."
        )
        setSong(mergedSong)
    }

    /**
     * 当 Hook 捕获到歌词构建完成时调用
     */
    fun onLyricsBuilt(nativeSongObj: Any, source: String) {
        val song = SongRepository.saveSong(nativeSongObj)
        if (song == null) {
            ProviderLogger.debug("PlaybackManager: Failed to save song.")
            return
        }
        val id = song.id?.takeIf { it.isNotBlank() } ?: return
        val translatedLines = song.lyrics?.count { !it.translation.isNullOrBlank() } ?: 0
        val lyricLines = song.lyrics?.size ?: 0
        ProviderLogger.debug(
            "PlaybackManager: Lyrics parsed from $source for $id, " +
                "lines=$lyricLines, translatedLines=$translatedLines"
        )

        val isSongSame by lazy {
            var same = false
            val time = measureTimeMillis {
                same = lastSong != song
            }
            Log.d("PlaybackManager", "Same song check took $time ms.")
            return@lazy same
        }

        if (id == currentSongId && isSongSame) {
            ProviderLogger.debug("PlaybackManager: Lyrics ready for current song $id, updating player.")
            setSong(song)
        } else {
            ProviderLogger.debug("PlaybackManager: Lyrics ready for song $id, but not current song.")
        }
    }

    private var lastSong: Song? = null

    internal fun mergeCatalogMetadata(currentSong: Song?, resolvedSong: Song): Song {
        val currentLyrics = currentSong
            ?.takeIf { it.id == resolvedSong.id }
            ?.lyrics
            ?.takeIf { it.isNotEmpty() }
        if (!resolvedSong.lyrics.isNullOrEmpty() || currentLyrics == null) return resolvedSong

        return resolvedSong.copy(
            name = resolvedSong.name ?: currentSong.name,
            artist = resolvedSong.artist ?: currentSong.artist,
            duration = resolvedSong.duration.takeIf { it > 0L } ?: currentSong.duration,
            metadata = resolvedSong.metadata ?: currentSong.metadata,
            lyrics = currentLyrics
        )
    }

    private fun setSong(song: Song?) {
        lastSong = song
        val sent = player?.setSong(song) ?: false
        val displayTranslation = PreferencesMonitor.isTranslationSelected()
        val translationSent = player?.setDisplayTranslation(displayTranslation) ?: false
        if (BuildConfig.DEBUG) {
            ProviderLogger.debug(
                "PlaybackManager: Sent song id=${song?.id}, success=$sent, " +
                    "secondaryLines=${song?.lyrics?.count { !it.secondary.isNullOrBlank() } ?: 0}, " +
                    "secondaryWordLines=${song?.lyrics?.count { !it.secondaryWords.isNullOrEmpty() } ?: 0}, " +
                    "displayTranslation=$displayTranslation, displayTranslationSuccess=$translationSent"
            )
        }
    }
}

/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.RemotePlayer
import io.github.proify.lyricon.amprovider.xposed.parser.AppleLyricsParserAccess
import kotlin.system.measureTimeMillis

object PlaybackManager {
    private var player: RemotePlayer? = null
    private var lyricRequester: LyricRequester? = null
    private var lyricsParserAccess: AppleLyricsParserAccess? = null

    // 状态追踪
    private var currentSongId: String? = null
    private var lastDisplayDiagnosticSignature: String? = null

    internal fun init(
        remotePlayer: RemotePlayer,
        requester: LyricRequester,
        hookResolver: AppleMusicHookResolver,
    ) {
        this.player = remotePlayer
        this.lyricRequester = requester
        this.lyricsParserAccess = AppleLyricsParserAccess.from(hookResolver)
    }

    /**
     * 当系统切歌或 Metadata 变化时调用
     */
    fun onSongChanged(newId: String?) {
        if (newId.isNullOrBlank()) {
            currentSongId = null
            setSong(null)
            ProviderLogger.debug("PlaybackManager: Song changed to null")
            logDisplayDiagnostic(null, "cleared", "song_id_missing")
            return
        }

        // 避免重复处理同一首歌
        if (newId == currentSongId) {
            logDisplayDiagnostic(lastSong, "skipped", "duplicate_song_id")
            return
        }
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
                lyrics.any { it.translation.isNullOrBlank() }
        if (needsLyrics || needsTranslation) {
            val queueId = MediaMetadataCache.getMetadataById(newId)?.queueId ?: 0L
            logDisplayDiagnostic(
                song,
                "pending",
                if (needsLyrics) "lyrics_download_requested" else "translation_download_requested",
                "queueId=$queueId",
            )
            lyricRequester?.requestDownload(newId, queueId)
        } else {
            ProviderLogger.debug("PlaybackManager: Song $newId has complete lyrics, skipping download.")
            logDisplayDiagnostic(song, "ready", "cached_lyrics_complete")
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
    fun onLyricsBuilt(
        nativeSongObj: Any,
        source: String,
        visibleSongId: String? = null,
        playbackSongId: String? = null,
    ) {
        val parserAccess = lyricsParserAccess
        if (parserAccess == null) {
            ProviderLogger.error("PlaybackManager: Lyrics parser runtime profile is unavailable")
            logDisplayDiagnostic(null, "skipped", "lyrics_parser_profile_missing", "source=$source")
            return
        }
        val song = SongRepository.saveSong(nativeSongObj, parserAccess)
        if (song == null) {
            ProviderLogger.debug("PlaybackManager: Failed to save song.")
            logDisplayDiagnostic(null, "skipped", "lyrics_parse_failed", "source=$source")
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

        val shouldPublish = shouldPublishBuiltLyrics(
            songId = id,
            currentSongId = currentSongId,
            visibleSongId = visibleSongId,
            playbackSongId = playbackSongId,
            source = source,
        )
        if (shouldPublish && isSongSame) {
            if (id != currentSongId) {
                currentSongId = id
                ProviderLogger.debug(
                    "PlaybackManager: Visible lyrics adopted as current song $id before playback."
                )
            }
            ProviderLogger.debug("PlaybackManager: Lyrics ready for current song $id, updating player.")
            logDisplayDiagnostic(
                song,
                "ready",
                if (id == visibleSongId) {
                    "lyrics_parsed_for_visible_song"
                } else {
                    "lyrics_parsed_for_current_song"
                },
                "source=$source, currentSongId=$currentSongId, " +
                    "visibleSongId=$visibleSongId, playbackSongId=$playbackSongId",
            )
            setSong(song)
        } else {
            ProviderLogger.debug("PlaybackManager: Lyrics ready for song $id, but not current song.")
            logDisplayDiagnostic(
                song,
                "skipped",
                "lyrics_for_non_current_song",
                "source=$source, currentSongId=$currentSongId, " +
                    "visibleSongId=$visibleSongId, playbackSongId=$playbackSongId",
            )
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

    internal fun shouldPublishBuiltLyrics(
        songId: String,
        currentSongId: String?,
        visibleSongId: String?,
        playbackSongId: String?,
        source: String,
    ): Boolean {
        if (playbackSongId != null && songId != playbackSongId) return false
        if (songId == currentSongId) return true
        return source == "apple" &&
            songId == visibleSongId &&
            songId == playbackSongId
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
            logDisplayDiagnostic(
                song = song,
                result = if (sent) "published" else "skipped",
                reason = if (sent) "song_sent_to_bridge" else "bridge_unavailable",
                extra = "displayTranslation=$displayTranslation, translationSent=$translationSent",
            )
        }
    }

    private fun logDisplayDiagnostic(
        song: Song?,
        result: String,
        reason: String,
        extra: String = "",
    ) {
        if (!BuildConfig.DEBUG) return
        val lyrics = song?.lyrics.orEmpty()
        val signature = listOf(
            song?.id,
            song?.name,
            lyrics.size,
            lyrics.count { !it.translation.isNullOrBlank() },
            result,
            reason,
            extra,
        ).joinToString("|")
        if (signature == lastDisplayDiagnosticSignature) return
        lastDisplayDiagnosticSignature = signature
        ProviderLogger.debug(
            "[DISPLAY_DIAG/AM] result=$result, reason=$reason, " +
                "songId=${sanitize(song?.id)}, title=${sanitize(song?.name)}, " +
                "artist=${sanitize(song?.artist)}, package=${Constants.APPLE_MUSIC_PACKAGE_NAME}, " +
                "duration=${song?.duration ?: 0L}, lyricLines=${lyrics.size}, " +
                "translatedLines=${lyrics.count { !it.translation.isNullOrBlank() }}, " +
                "backingLines=${lyrics.count { !it.secondary.isNullOrBlank() }}, $extra"
        )
    }

    private fun sanitize(value: String?): String = value
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(80)
        .orEmpty()
}

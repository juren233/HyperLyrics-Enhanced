/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.cache

import android.content.Context
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.online.model.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Remembers which online source each cached song last showed its lyric body, translation and
 * pronunciation from (「上次歌词」「上次翻译」「上次发音」 on the song page). Fed by the SystemUI
 * timeline when it applies content, so it is what was really on screen, not what a search fetched:
 * a fetch path files its song under [KEY_TAG] and, when no other key names the source of the lyric
 * body, [KEY_SOURCE].
 */
object OnlineLyricCacheUsage {
    /**
     * Song metadata naming the cache song a shown result is filed under. Kept out of the shared
     * [LyricMetadataKeys] contract: it never leaves SystemUI (Lyricon adds it only to what it hands the timeline).
     */
    const val KEY_TAG = "onlineLyricCacheTag"

    /** The source of an online lyric body, for fetch paths that no other key names it for. */
    const val KEY_SOURCE = "onlineLyricCacheSource"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastRecorded: Pair<String, OnlineLyricCacheLastUsed>? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * The sources [song] shows online content from. The lyric body names its source; translation and
     * pronunciation are the online completion's, else the body's own lines when an online body carries them.
     */
    fun usedSourcesOf(song: Song): OnlineLyricCacheLastUsed {
        val metadata = song.metadata ?: return OnlineLyricCacheLastUsed()
        fun sourceAt(key: String) = metadata.getString(key)?.takeIf { name -> Source.entries.any { it.name == name } }
        val lyrics = sourceAt(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE) ?: sourceAt(KEY_SOURCE)
        val lines = song.lyrics.orEmpty()
        return OnlineLyricCacheLastUsed(
            lyrics = lyrics,
            translation = sourceAt(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE)
                ?: lyrics?.takeIf { lines.any { !it.translation.isNullOrBlank() } },
            pronunciation = sourceAt(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE)
                ?: lyrics?.takeIf { lines.any { !it.roma.isNullOrBlank() } },
        )
    }

    /** Whether [song] shows any online content. */
    fun showsOnlineContent(song: Song): Boolean = !usedSourcesOf(song).isEmpty

    internal fun usedSources(song: Song): Pair<String, OnlineLyricCacheLastUsed>? {
        val tag = song.metadata?.getString(KEY_TAG)?.takeIf(String::isNotBlank) ?: return null
        return usedSourcesOf(song).takeUnless { it.isEmpty }?.let { tag to it }
    }

    /**
     * [song] filed under the cache song [tag]; [source] names the source of its online lyric body
     * when no other metadata does. A null [tag] leaves the song as it is.
     */
    fun filedUnder(song: Song, tag: String?, source: Source? = null): Song {
        if (tag.isNullOrBlank()) return song
        val replaced = setOfNotNull(KEY_TAG, KEY_SOURCE.takeIf { source != null })
        val pairs = song.metadata?.entries.orEmpty()
            .filterNot { it.key in replaced }
            .map { it.key to it.value } +
            listOfNotNull(KEY_TAG to tag, source?.let { KEY_SOURCE to it.name })
        return song.copy(metadata = lyricMetadataOf(*pairs.toTypedArray()))
    }

    /**
     * Records what [song] shows from which source; a display without online content keeps the
     * previous record, repeated revisions showing the same sources write once.
     */
    fun onShown(song: Song?) {
        val context = appContext ?: return
        val used = song?.let(::usedSources) ?: return
        if (used == lastRecorded) return
        lastRecorded = used
        scope.launch { CachedSearchSource.cacheFor(context).markUsed(used.first, used.second) }
    }
}

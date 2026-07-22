/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.lyric.model

import com.juren233.hyperlyricsenhanced.lyric.model.extensions.deepCopy
import com.juren233.hyperlyricsenhanced.lyric.model.extensions.normalizeSortByTime
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.DeepCopyable
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.Normalize
import kotlinx.serialization.Serializable

/**
 * 歌曲信息
 *
 * @property id 歌曲ID
 * @property name 歌曲名
 * @property artist 艺术家
 * @property duration 歌曲时长
 * @property metadata 元数据
 * @property lyrics 歌词列表
 */
@Serializable
data class Song(
    var id: String? = null,
    var name: String? = null,
    var artist: String? = null,
    var duration: Long = 0,
    var metadata: LyricMetadata? = null,
    var lyrics: List<RichLyricLine>? = null,
) : DeepCopyable<Song>, Normalize<Song> {

    override fun deepCopy(): Song = copy(lyrics = lyrics?.deepCopy())

    override fun normalize(): Song = deepCopy().apply {
        lyrics = lyrics?.mapNotNull { line ->
            if (line.duration <= 0) line.duration = line.end - line.begin

            val isValid = line.begin >= 0
                    && line.begin < line.end
                    && line.duration > 0
                    && !line.text.isNullOrBlank()
            if (isValid) line else null
        }?.normalizeSortByTime()
    }
}

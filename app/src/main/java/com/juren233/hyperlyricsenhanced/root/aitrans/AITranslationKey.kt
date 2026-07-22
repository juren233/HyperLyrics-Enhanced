package com.juren233.hyperlyricsenhanced.root.aitrans

import com.juren233.hyperlyricsenhanced.common.extensions.md5
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.style.AiTranslationConfigs

internal object AITranslationKey {
    fun calculate(configs: AiTranslationConfigs, song: Song, lines: List<String>): String {
        return buildString {
            append("target=").appendLine(configs.targetLanguage.orEmpty())
            append("title=").appendLine(song.name.orEmpty())
            append("artist=").appendLine(song.artist.orEmpty())
            lines.forEachIndexed { index, line ->
                append(index).append(':').appendLine(line)
            }
        }.md5()
    }
}




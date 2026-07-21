package com.lidesheng.hyperlyric.root.aitrans

import com.lidesheng.hyperlyric.common.extensions.md5
import com.lidesheng.hyperlyric.lyric.model.Song
import com.lidesheng.hyperlyric.lyric.style.AiTranslationConfigs

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




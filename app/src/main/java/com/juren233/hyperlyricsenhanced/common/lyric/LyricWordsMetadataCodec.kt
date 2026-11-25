package com.juren233.hyperlyricsenhanced.common.lyric

import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 把一段逐字时间轴放进字符串型的歌词元数据里。
 *
 * 重叠分组只能把每句的伴唱作为元数据文本带走，息屏逐字需要伴唱自己的逐字时间，
 * 所以分组时顺带把这些逐字编码进元数据，读取时再解回来。
 */
internal object LyricWordsMetadataCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val serializer = ListSerializer(LyricWord.serializer())

    fun encode(words: List<LyricWord>?): String? {
        if (words.isNullOrEmpty()) return null
        return runCatching { json.encodeToString(serializer, words) }.getOrNull()
    }

    fun decode(encoded: String?): List<LyricWord>? {
        if (encoded.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(serializer, encoded) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }
}

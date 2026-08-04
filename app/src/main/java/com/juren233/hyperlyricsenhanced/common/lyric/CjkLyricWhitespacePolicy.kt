package com.juren233.hyperlyricsenhanced.common.lyric

import com.juren233.hyperlyricsenhanced.lyric.model.LyricMetadata
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine

/**
 * 中日韩歌词展示层空白处理策略。
 *
 * 只要一段文本包含汉字、平假名、片假名或谚文，就移除其中的空白字符；
 * 纯拉丁文本保持原样。策略只创建展示副本，不修改歌词源或缓存。
 */
internal object CjkLyricWhitespacePolicy {
    private val displayedMetadataKeys = setOf(
        LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION,
        LyricMetadataKeys.OVERLAPPING_PRIMARY_BACKING,
        LyricMetadataKeys.OVERLAPPING_PRIMARY_BACKING_TRANSLATION,
        LyricMetadataKeys.OVERLAPPING_SECONDARY_TRANSLATION,
        LyricMetadataKeys.OVERLAPPING_SECONDARY_BACKING,
        LyricMetadataKeys.OVERLAPPING_SECONDARY_BACKING_TRANSLATION,
    )

    /**
     * 移除含中日韩文本中的所有 Unicode 空白，保留纯拉丁文本的原始间距。
     */
    fun transformText(text: String?): String? {
        text ?: return null
        if (!containsCjk(text)) return text
        return removeWhitespace(text)
    }

    /**
     * 复制并处理最终可见的歌词字段与逐字文本，发音/roma 特意保持原样。
     */
    fun transformLine(line: IRichLyricLine): RichLyricLine = RichLyricLine(
        begin = line.begin,
        end = line.end,
        duration = line.duration,
        isAlignedRight = line.isAlignedRight,
        metadata = transformMetadata(line.metadata),
        text = transformText(line.text),
        words = transformWords(line.words, line.text),
        secondary = transformText(line.secondary),
        secondaryWords = transformWords(line.secondaryWords, line.secondary),
        translation = transformText(line.translation),
        translationWords = transformWords(line.translationWords, line.translation),
        roma = line.roma,
    )

    /**
     * 同步处理会被 AOD 读取的伴唱、重叠歌词及其翻译元数据。
     */
    private fun transformMetadata(metadata: LyricMetadata?): LyricMetadata? {
        metadata ?: return null
        return LyricMetadata(
            metadata.mapValues { (key, value) ->
                if (key in displayedMetadataKeys) transformText(value) else value
            }
        )
    }

    /**
     * 保留每个逐字片段的原始时间轴，只改写其展示文本。
     */
    private fun transformWords(
        words: List<LyricWord>?,
        fallbackText: String?,
    ): List<LyricWord>? {
        words ?: return null
        val completeText = fallbackText.orEmpty() + words.joinToString("") {
            it.text.orEmpty()
        }
        if (!containsCjk(completeText)) return words
        return words.map { word ->
            word.copy(text = removeWhitespace(word.text))
        }
    }

    /**
     * 在整行已确认为中日韩歌词后，空白词片段也要清空，避免逐字轨道重新显示空格。
     */
    private fun removeWhitespace(text: String?): String? {
        text ?: return null
        return buildString(text.length) {
            text.codePoints().forEach { codePoint ->
                if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                    appendCodePoint(codePoint)
                }
            }
        }
    }

    /**
     * 按 Unicode Script 判定中日韩字符，同时覆盖辅助平面的扩展汉字。
     */
    private fun containsCjk(text: String): Boolean = text.codePoints().anyMatch { codePoint ->
        when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL -> true
            else -> false
        }
    }
}

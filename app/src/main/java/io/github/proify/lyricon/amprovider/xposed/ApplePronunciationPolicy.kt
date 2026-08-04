package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy

internal object ApplePronunciationPolicy {
    fun nonNullDisplayText(text: String?): String = text.orEmpty()

    /**
     * Whether a completed Apple lyric model must trigger a presentation refresh.
     *
     * Official Apple data is the primary source, but it can become available after the
     * first lyrics-page presentation. The refresh decision therefore cannot depend only
     * on the third-party fallback store.
     */
    fun shouldRefreshPresentationAfterBuild(
        sourceIsApple: Boolean,
        hasValidOfficialPronunciation: Boolean,
        hasOnlineTranslation: Boolean,
        hasOnlinePronunciation: Boolean,
        pronunciationSelected: Boolean,
    ): Boolean {
        if (!sourceIsApple) return false
        if (hasOnlineTranslation || hasOnlinePronunciation) return true
        return pronunciationSelected && hasValidOfficialPronunciation
    }

    fun wordTrack(
        hasValidOfficialPronunciation: Boolean,
        hasOnlinePronunciation: Boolean,
    ): ApplePronunciationWordTrack = when {
        hasValidOfficialPronunciation -> ApplePronunciationWordTrack.OFFICIAL
        hasOnlinePronunciation -> ApplePronunciationWordTrack.MAIN_LINE_TIMING
        else -> ApplePronunciationWordTrack.HIDDEN
    }

    /**
     * Apple Music 6.5.0 uses the pronunciation word begin as an exact lookup key while
     * composing the two-line karaoke layout. A valid official text/vector is therefore
     * not enough: every visible main-line word must have a matching pronunciation key.
     *
     * When this is false, callers keep the official text but render it on the native main
     * word/time vector. This preserves Apple-source precedence without creating synthetic
     * native words or inheriting an incompatible pronunciation timeline.
     */
    fun hasCompatibleOfficialWordTiming(
        mainWordBegins: List<Int>,
        pronunciationWordBegins: List<Int>,
    ): Boolean {
        val main = mainWordBegins.filter { it >= 0 }.distinct()
        val pronunciation = pronunciationWordBegins.filter { it >= 0 }.toHashSet()
        return main.isNotEmpty() && pronunciation.isNotEmpty() &&
            main.all(pronunciation::contains)
    }

    /**
     * 将整行发音文本按 Apple 原生主句 word 的实际文本权重分配。
     *
     * Apple 可能把多个汉字合并为一个 word，例如“潇洒 / 的 / 放 / 屁”只有 4 个
     * native word，但需要容纳 5 个粤拼音节。只按 word 数均分会使后续音节错位，
     * 因此东亚文字按字符数计权，其他非空 word 按一个发音单位计权。
     *
     * 这里只生成显示片段，不创建新的 native LyricsWord。实际渲染继续复用主句 word，
     * 因而每个片段都保留 Apple 原生父歌词行、lineId、wordId 与时间轴。
     */
    fun displaySegments(
        pronunciation: String,
        mainWordTexts: List<String>,
    ): List<String> {
        if (mainWordTexts.isEmpty()) return emptyList()
        val tokens = pronunciation.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (tokens.isEmpty()) return emptyList()

        val measuredWeights = mainWordTexts.map(::pronunciationUnitCount)
        val weights = if (measuredWeights.any { it > 0 }) {
            measuredWeights
        } else {
            List(mainWordTexts.size) { 1 }
        }
        val totalWeight = weights.sum()
        var consumedWeight = 0
        return weights.map { weight ->
            val tokenStart = consumedWeight * tokens.size / totalWeight
            consumedWeight += weight
            val tokenEnd = consumedWeight * tokens.size / totalWeight
            tokens.subList(tokenStart, tokenEnd).joinToString(" ")
        }
    }

    private fun pronunciationUnitCount(text: String): Int {
        val visibleText = text.replace(HTML_TAG, " ").trim()
        if (visibleText.isEmpty()) return 0

        var eastAsianCharacters = 0
        var hasLetterOrDigit = false
        var index = 0
        while (index < visibleText.length) {
            val codePoint = visibleText.codePointAt(index)
            if (Character.isLetterOrDigit(codePoint)) {
                hasLetterOrDigit = true
                when (Character.UnicodeScript.of(codePoint)) {
                    Character.UnicodeScript.HAN,
                    Character.UnicodeScript.HIRAGANA,
                    Character.UnicodeScript.KATAKANA,
                    Character.UnicodeScript.HANGUL -> eastAsianCharacters++
                    else -> Unit
                }
            }
            index += Character.charCount(codePoint)
        }
        return eastAsianCharacters.takeIf { it > 0 } ?: if (hasLetterOrDigit) 1 else 0
    }

    fun selectLanguage(
        systemMatch: String?,
        appleLanguages: List<String>,
        onlineFallbackLanguage: String?,
    ): String? =
        systemMatch.normalizedLatinLanguage()
            ?: appleLanguages.firstNotNullOfOrNull { it.normalizedLatinLanguage() }
            ?: onlineFallbackLanguage.normalizedLatinLanguage()

    private fun String?.normalizedLatinLanguage(): String? =
        this?.trim()?.takeIf {
            it.isNotEmpty() && RomanizationPolicy.isLatinLanguageTag(it)
        }

    private val HTML_TAG = Regex("<[^>]*>")
}

internal enum class ApplePronunciationWordTrack {
    OFFICIAL,
    MAIN_LINE_TIMING,
    HIDDEN,
}

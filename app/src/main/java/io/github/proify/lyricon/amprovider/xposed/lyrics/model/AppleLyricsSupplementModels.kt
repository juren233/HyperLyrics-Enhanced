/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

internal data class ApplePronunciationContext(
    val songId: String?,
    val pronunciationLanguages: List<String>,
)

internal enum class AppleNativeSupplementTrack {
    PRONUNCIATION,
    TRANSLATION,
}

internal data class ApplePronunciationRenderPlan(
    val pronunciation: String,
)

internal data class ApplePronunciationWordRenderContext(
    val displayTextByWord: Map<ApplePronunciationWordKey, String>,
) {
    /** 仅当当前对象属于本次发音渲染计划时返回覆盖文本。 */
    fun displayText(word: Any?): String? {
        val key = applePronunciationWordKey(word) ?: return null
        return displayTextByWord[key]
    }
}

internal data class ApplePronunciationWordKey(
    val wordId: Int,
    val begin: Int,
    val end: Int,
)

/** JavaCPP 可能重复创建 wrapper，因此使用 native word 的稳定字段匹配渲染文本。 */
internal fun applePronunciationWordKey(word: Any?): ApplePronunciationWordKey? {
    word ?: return null
    return runCatching {
        ApplePronunciationWordKey(
            wordId = (AppleReflection.call(word, "getWordId") as Number).toInt(),
            begin = (AppleReflection.call(word, "getBegin") as Number).toInt(),
            end = (AppleReflection.call(word, "getEnd") as Number).toInt(),
        )
    }.getOrNull()
}

internal data class AppleLyricsLanguageParts(
    val normalized: String,
    val language: String,
    val script: String?,
    val region: String?,
)

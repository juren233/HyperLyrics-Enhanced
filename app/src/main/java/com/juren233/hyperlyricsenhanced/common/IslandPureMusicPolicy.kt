/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

/**
 * 纯音乐判定：整首歌没有可显示歌词，或可显示歌词不超过 [MAX_PURE_MUSIC_LINES] 行且其中任一行包含用户配置的关键词。
 * 行数上限用来避免正常歌词碰巧出现关键词时被误判（用户 2026-11-06 确定）。
 */
object IslandPureMusicPolicy {
    /** 预设关键词，用户可在设置页自行增删；一行一个。 */
    val DEFAULT_KEYWORDS: List<String> = listOf(
        "纯音乐，请欣赏",
        "此歌曲为没有填词的纯音乐，请您欣赏",
        "此歌曲为纯音乐，请欣赏",
        "纯音乐",
        "暂无歌词",
        "Instrumental",
    )

    /** 引入“已见预设”记录之前的预设；旧版本保存过列表的用户按它推算哪些预设是新增的。 */
    private val LEGACY_DEFAULT_KEYWORDS: List<String> = listOf(
        "纯音乐，请欣赏",
        "此歌曲为没有填词的纯音乐，请您欣赏",
        "纯音乐",
        "暂无歌词",
        "Instrumental",
    )

    const val MAX_PURE_MUSIC_LINES = 5

    const val KEYWORD_SEPARATOR = "\n"

    val DEFAULT_KEYWORDS_RAW: String = DEFAULT_KEYWORDS.joinToString(KEYWORD_SEPARATOR)

    fun parseKeywords(raw: String?): List<String> =
        (raw ?: DEFAULT_KEYWORDS_RAW)
            .split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * 实际生效的关键词：用户保存的列表，加上保存之后新增的预设。
     * @param raw 用户保存的列表，null 表示从未编辑，直接用最新预设。
     * @param seenDefaultsRaw 保存列表时的预设；null 表示旧版本保存的，按引入记录前的预设推算。
     */
    fun effectiveKeywords(raw: String?, seenDefaultsRaw: String?): List<String> {
        if (raw == null) return DEFAULT_KEYWORDS
        val seen = seenDefaultsRaw?.let { parseKeywords(it) } ?: LEGACY_DEFAULT_KEYWORDS
        return (parseKeywords(raw) + DEFAULT_KEYWORDS.filter { it !in seen }).distinct()
    }

    fun effectiveKeywordSet(raw: String?, seenDefaultsRaw: String?): Set<String> =
        effectiveKeywords(raw, seenDefaultsRaw).map(::normalize).filter { it.isNotEmpty() }.toSet()

    fun serializeKeywords(keywords: List<String>): String =
        keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(KEYWORD_SEPARATOR)

    /** 忽略大小写、空白和标点，避免全半角逗号等写法差异造成漏判。 */
    fun normalize(text: String?): String =
        text.orEmpty().filter { it.isLetterOrDigit() }.lowercase()

    fun normalizedKeywordSet(raw: String?): Set<String> =
        parseKeywords(raw).map(::normalize).filter { it.isNotEmpty() }.toSet()

    /**
     * @param lineTexts 去掉占位标题行后的全部歌词行文本。
     */
    fun isPureMusic(lineTexts: List<String?>, normalizedKeywords: Set<String>): Boolean {
        val visible = lineTexts.map(::normalize).filter { it.isNotEmpty() }
        if (visible.isEmpty()) return true
        if (visible.size > MAX_PURE_MUSIC_LINES) return false
        return visible.any { line -> normalizedKeywords.any { it in line } }
    }
}

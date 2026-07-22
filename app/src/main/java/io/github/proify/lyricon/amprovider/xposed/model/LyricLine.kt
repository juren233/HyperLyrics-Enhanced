/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.model

import kotlinx.serialization.Serializable

@Serializable
data class LyricLine(
    override var agent: String? = null,
    override var begin: Int = 0,
    override var duration: Int = 0,
    override var end: Int = 0,

    var htmlLineText: String? = null, //主要歌词
    var words: MutableList<LyricWord> = mutableListOf(), // 主要歌词单词
    var htmlTranslationLineText: String? = null, // 主要翻译

    // 副歌词
    var htmlBackgroundVocalsLineText: String? = null,
    var backgroundWords: MutableList<LyricWord> = mutableListOf(),

    var htmlTranslatedBackgroundVocalsLineText: String? = null,
    //var translatedBackgroundWords: MutableList<LyricWord> = mutableListOf(),

    // 音译
    var htmlPronunciationLineText: String? = null,
    //var pronunciationWords: MutableList<LyricWord> = mutableListOf(),

    var htmlPronunciationBackgroundVocalsLineText: String? = null,
    //var pronunciationBackgroundWords: MutableList<LyricWord> = mutableListOf(),

) : LyricTiming
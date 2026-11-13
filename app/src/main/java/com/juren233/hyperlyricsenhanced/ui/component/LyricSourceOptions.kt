/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.ui.component

import androidx.annotation.StringRes
import com.juren233.hyperlyricsenhanced.R

/** 歌词来源的存储值与显示名称，歌词设置页、引导页与主页共用这一份。 */
object LyricSourceOptions {
    val ids = listOf("lyricon", "superlyric", "lyricinfo")

    @StringRes
    val labels = listOf(
        R.string.lyric_source_lyricon,
        R.string.lyric_source_superlyric,
        R.string.lyric_source_lyricinfo,
    )

    @StringRes
    fun labelOf(id: String?): Int = labels[ids.indexOf(id).coerceAtLeast(0)]
}

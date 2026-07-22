package com.juren233.hyperlyricsenhanced.lyric.source

/** 歌词源切换时的状态重置接口，由 Root 层实现 */
fun interface StateResetter {
    fun clearState()
}

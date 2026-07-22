package com.juren233.hyperlyricsenhanced.lyric.source

interface LyricSource {
    val id: String
    val displayName: String
    fun start(sink: LyricSink)
    fun stop()
    fun isAvailable(): Boolean
}

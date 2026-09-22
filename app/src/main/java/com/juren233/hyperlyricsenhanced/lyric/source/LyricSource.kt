package com.juren233.hyperlyricsenhanced.lyric.source

interface LyricSource {
    val id: String
    val displayName: String
    /** Whether this source reports a lyric clock that can anchor the unified timeline. */
    val providesLyricClock: Boolean get() = false
    fun start(sink: LyricSink)
    fun stop()
    fun isAvailable(): Boolean
}

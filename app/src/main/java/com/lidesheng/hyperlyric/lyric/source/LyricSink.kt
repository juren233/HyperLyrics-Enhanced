package com.lidesheng.hyperlyric.lyric.source

interface LyricSink {
    fun onSongChanged(song: Any?)
    fun onLyricLine(line: Any?)
    fun onPlainText(text: String?)
    fun onStop()
    fun onMetadata(title: String?, artist: String?, album: String?, publisher: String? = null)
    fun onPlaybackStateChanged(isPlaying: Boolean)
    fun onPositionChanged(position: Long)
}

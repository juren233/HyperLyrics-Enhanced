package com.juren233.hyperlyricsenhanced.lyric.source

interface LyricSink {
    /**
     * 完整歌词内容入口。统一时间轴实现应覆盖本方法，并把 [content] 作为一次不可变内容修订；
     * 旧 sink 的默认实现仅用于兼容尚未接入统一时间轴的调用方。
     */
    fun onTimelineContent(content: TimelineContent) {
        if (content.onlineTranslationMatched) {
            onOnlineTranslationMatched(content.song)
        } else {
            onSongChanged(content.song)
        }
    }

    fun onSongChanged(song: Any?)
    fun onLyricLine(line: Any?)
    fun onPlainText(text: String?)

    /**
     * 来源侧（app 进程内）播放态提示。非时钟、非渲染指令：统一时间轴用它做缓冲期
     * 播放态平滑（部分 app 缓冲期向 MediaSession 上报暂停态，app 内部仍为播放中）。
     */
    fun onSourcePlaybackHint(playing: Boolean, packageName: String?) = Unit

    /**
     * 同一播放器内切歌时清除旧内容，但保留当前渲染宿主与播放态。
     *
     * 旧 sink 默认仍按完整停止处理；SystemUI 的统一时间轴 sink 会覆盖本方法，避免把
     * 同包曲目替换误当成整个媒体来源停止，造成超级岛短暂收回。
     */
    fun onTrackTransition(
        title: String?,
        artist: String?,
        album: String?,
        publisher: String?,
    ) {
        onStop()
        onMetadata(title, artist, album, publisher)
    }

    fun onStop()
    fun onMetadata(title: String?, artist: String?, album: String?, publisher: String? = null)
    fun onPlaybackStateChanged(isPlaying: Boolean)
    /** Actual consumer state, when observable; null preserves event-only sinks. */
    fun currentPlaybackState(): Boolean? = null
    fun onPositionChanged(position: Long)
    fun onSeekTo(position: Long) = onPositionChanged(position)
    fun onOnlineTranslationMatched(song: Any?) = onSongChanged(song)
    fun onOnlineTranslationUnavailable(song: Any?) = Unit
}

/** SourceManager 用它声明唯一活动来源，避免已停止来源的迟到回调进入时间轴。 */
interface SourceSelectionAwareSink {
    fun onSourceSelected(sourceId: String)
    fun onSourceStopped(sourceId: String)
}

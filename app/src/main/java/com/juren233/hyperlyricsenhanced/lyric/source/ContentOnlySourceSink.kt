/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.source

import com.juren233.hyperlyricsenhanced.lyric.model.Song

/**
 * 单一时间轴的来源边界。
 *
 * 只允许当前来源提交歌词内容和生命周期事件。完整 Song 直接进入统一时间轴；仅能提供
 * 当前行的兼容来源可提交行内容。仅声明了歌词时钟能力的活动来源可提交位置/seek，
 * 由统一时间轴处理；来源播放态不直接进入渲染层。
 */
internal class ContentOnlySourceSink(
    private val sourceId: String,
    private val isActive: (String) -> Boolean,
    private val delegate: LyricSink,
    private val allowSourceClock: Boolean = false,
) : LyricSink {
    private inline fun ifActive(action: () -> Unit) {
        if (isActive(sourceId)) action()
    }

    override fun onTimelineContent(content: TimelineContent) = ifActive {
        delegate.onTimelineContent(content.copy(sourceId = sourceId))
    }

    override fun onSongChanged(song: Any?) = ifActive {
        delegate.onTimelineContent(
            TimelineContent(
                sourceId = sourceId,
                track = null,
                song = song as? Song,
            )
        )
    }

    override fun onOnlineTranslationMatched(song: Any?) = ifActive {
        delegate.onTimelineContent(
            TimelineContent(
                sourceId = sourceId,
                track = null,
                song = song as? Song,
                onlineTranslationMatched = true,
            )
        )
    }

    override fun onStop() = ifActive(delegate::onStop)

    override fun currentPlaybackState(): Boolean? =
        if (isActive(sourceId)) delegate.currentPlaybackState() else null

    override fun onOnlineTranslationUnavailable(song: Any?) = ifActive {
        delegate.onOnlineTranslationUnavailable(song)
    }

    override fun onLyricLine(line: Any?) = ifActive { delegate.onLyricLine(line) }
    override fun onPlainText(text: String?) = ifActive { delegate.onPlainText(text) }
    override fun onSourcePlaybackHint(playing: Boolean, packageName: String?) = ifActive {
        delegate.onSourcePlaybackHint(playing, packageName)
    }
    override fun onMetadata(title: String?, artist: String?, album: String?, publisher: String?) = Unit
    override fun onPlaybackStateChanged(isPlaying: Boolean) = Unit
    override fun onPositionChanged(position: Long) {
        if (allowSourceClock) ifActive { delegate.onPositionChanged(position) }
    }
    override fun onSeekTo(position: Long) {
        if (allowSourceClock) ifActive { delegate.onSeekTo(position) }
    }
}

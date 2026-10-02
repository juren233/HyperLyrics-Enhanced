/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.lyric.source

import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity

/**
 * 歌词来源交给 SystemUI 唯一时间轴的完整内容修订。
 *
 * 来源只负责内容，不携带播放位置，也不得决定当前行。跨进程来源必须提供严格的
 * [track]；SystemUI 进程内来源可提供字段不完整但确定的身份，由时间轴按已知字段精确匹配。
 */
data class TimelineContent(
    val sourceId: String,
    val track: TrackIdentity?,
    val song: Song?,
    val fallbackTitle: String = "",
    val revision: Long = 0L,
    val strictIdentity: Boolean = false,
    val onlineTranslationMatched: Boolean = false,
    /** true 表示来源只能逐行提供内容；仍由统一时间轴持有并滚动，不启用来源时钟。 */
    val streaming: Boolean = false,
)

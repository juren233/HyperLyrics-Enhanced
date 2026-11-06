/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import com.juren233.hyperlyricsenhanced.online.model.Source

/**
 * 超级岛上在线兜底歌词的「先显示、后切换」状态：先到的结果作为预览显示，
 * 更高优先级的结果到达后挂起，等到下一句歌词的起点再切过去，避免当前句跳变。
 *
 * 只管岛端发布，不涉及 Apple Music 应用内的歌词页。
 */
internal class AppleFallbackIslandSwitch {
    class Preview(val generation: Int, val song: LocalSong, val source: Source?)

    class Pending(val generation: Int, val targetPosition: Long, val commit: () -> Unit)

    private var preview: Preview? = null
    private var pending: Pending? = null

    @Synchronized
    fun showPreview(value: Preview) {
        preview = value
        pending = null
    }

    @Synchronized
    fun previewFor(generation: Int): Preview? = preview?.takeIf { it.generation == generation }

    @Synchronized
    fun defer(value: Pending) {
        pending = value
    }

    /** 到达挂起切换的起点则取出；已过期（代际不是当前）的直接丢弃。 */
    @Synchronized
    fun takeDue(position: Long, isCurrent: (Int) -> Boolean): Pending? {
        val value = pending ?: return null
        if (!isCurrent(value.generation)) {
            pending = null
            return null
        }
        if (position < value.targetPosition) return null
        pending = null
        return value
    }

    @Synchronized
    fun clear() {
        preview = null
        pending = null
    }
}

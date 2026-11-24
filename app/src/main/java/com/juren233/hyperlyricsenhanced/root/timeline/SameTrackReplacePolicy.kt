/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

/**
 * 同一首歌的整份歌词在播放中被替换（翻译到达、补充/逐字来源到达、来源重发同一份歌词）时，
 * 要不要等到当前句自然换句再换。句中替换会让岛与息屏把当前句重新绑定：进度、逐字与跑马灯
 * 从头来一次，看起来就是一次「抽搐」。只做判定，挂起与提交由时间轴驱动负责。
 */
internal object SameTrackReplacePolicy {

    /** 挂起最长这么久仍没换句（超长句、最后一句）就直接应用，不让新内容无限期等待。 */
    const val MAX_DEFER_MS = 10_000L

    fun shouldDefer(
        streaming: Boolean,
        sameTrack: Boolean,
        playing: Boolean,
        displayedHasLyrics: Boolean,
        lineShowing: Boolean,
    ): Boolean = !streaming && sameTrack && playing && displayedHasLyrics && lineShowing
}

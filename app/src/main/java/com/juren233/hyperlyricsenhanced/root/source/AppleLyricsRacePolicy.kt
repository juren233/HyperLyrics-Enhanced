/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

/**
 * 「自动」来源下 Apple Music 在线兜底的竞速裁决：ttml 与搜索源同时开始，
 * 谁先出结果谁先给超级岛显示；ttml 的结果无论多晚到都优先于搜索源，AMLL 优先于 LB。
 *
 * 只做裁决，不碰网络和发布，便于单测。ttml 列表按优先级排列（下标越小优先级越高）。
 */
internal object AppleLyricsRacePolicy {
    enum class State { PENDING, MISS, HIT }

    sealed interface Decision {
        /** 还不能定论，也没有可以先给岛显示的结果。 */
        data object Wait : Decision

        /** 先给岛显示的结果：[ttmlIndex] 为 null 表示搜索源，否则是该下标的 ttml。 */
        data class Early(val ttmlIndex: Int?) : Decision

        /** 最终采用该下标的 ttml。 */
        data class FinalTtml(val index: Int) : Decision

        /** 所有 ttml 都没有，最终采用搜索源（搜索源本身也可能没有歌词）。 */
        data object FinalSearch : Decision
    }

    fun decide(ttml: List<State>, search: State, earlyPublished: Boolean): Decision {
        for (index in ttml.indices) {
            when (ttml[index]) {
                State.HIT -> return Decision.FinalTtml(index)
                State.PENDING -> return earlyOrWait(ttml, index, search, earlyPublished)
                State.MISS -> Unit
            }
        }
        return if (search == State.PENDING) Decision.Wait else Decision.FinalSearch
    }

    /** 更高优先级的 ttml 还没回来：已有可显示的结果就先显示，但最终结论要等它。 */
    private fun earlyOrWait(
        ttml: List<State>,
        pendingIndex: Int,
        search: State,
        earlyPublished: Boolean,
    ): Decision {
        if (earlyPublished) return Decision.Wait
        val lowerTtml = (pendingIndex + 1 until ttml.size).firstOrNull { ttml[it] == State.HIT }
        if (lowerTtml != null) return Decision.Early(lowerTtml)
        if (search == State.HIT) return Decision.Early(null)
        return Decision.Wait
    }
}

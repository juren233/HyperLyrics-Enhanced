/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

/**
 * 「自动」来源下在线兜底的竞速裁决：ttml 与搜索源同时开始，
 * 谁先出结果谁先给超级岛显示；ttml 的结果无论多晚到都优先于搜索源，AMLL 优先于 LB。
 * 搜索源自身也会逐步升级（先到的逐行，之后更早返回的逐字），每次升级都可再显示一次。
 *
 * 只做裁决，不碰网络和发布，便于单测。ttml 列表按优先级排列（下标越小优先级越高）。
 */
internal object AppleLyricsRacePolicy {
    enum class State { PENDING, MISS, HIT }

    sealed interface Decision {
        /** 还不能定论，也没有比已显示内容更好的结果。 */
        data object Wait : Decision

        /** 先给岛显示的结果：[ttmlIndex] 为 null 表示搜索源当前最好的结果，否则是该下标的 ttml。 */
        data class Early(val ttmlIndex: Int?) : Decision

        /** 最终采用该下标的 ttml。 */
        data class FinalTtml(val index: Int) : Decision

        /** 所有 ttml 都没有，最终采用搜索源（搜索源本身也可能没有歌词）。 */
        data object FinalSearch : Decision
    }

    /**
     * @param search 搜索源是否已经结束、结束时有没有歌词。
     * @param freshSearch 搜索源有尚未给岛显示过的结果（中途升级或最终结果）。
     * @param publishedTtml 已经先给岛显示的 ttml 下标；null 表示还没有显示过 ttml。
     */
    fun decide(
        ttml: List<State>,
        search: State,
        freshSearch: Boolean,
        publishedTtml: Int?,
    ): Decision {
        for (index in ttml.indices) {
            when (ttml[index]) {
                State.HIT -> return Decision.FinalTtml(index)
                State.PENDING -> return earlyOrWait(ttml, index, freshSearch, publishedTtml)
                State.MISS -> Unit
            }
        }
        if (search != State.PENDING) return Decision.FinalSearch
        // ttml 都没有，搜索源还在等更好的来源：已有的先显示。
        return if (freshSearch) Decision.Early(null) else Decision.Wait
    }

    /** 更高优先级的 ttml 还没回来：有比已显示内容更好的结果就先显示，但最终结论要等它。 */
    private fun earlyOrWait(
        ttml: List<State>,
        pendingIndex: Int,
        freshSearch: Boolean,
        publishedTtml: Int?,
    ): Decision {
        val lowerTtml = (pendingIndex + 1 until ttml.size).firstOrNull { ttml[it] == State.HIT }
        if (lowerTtml != null) {
            return if (publishedTtml == null || lowerTtml < publishedTtml) Decision.Early(lowerTtml) else Decision.Wait
        }
        // 已显示的 ttml 高于任何搜索源结果。
        if (publishedTtml != null) return Decision.Wait
        return if (freshSearch) Decision.Early(null) else Decision.Wait
    }
}

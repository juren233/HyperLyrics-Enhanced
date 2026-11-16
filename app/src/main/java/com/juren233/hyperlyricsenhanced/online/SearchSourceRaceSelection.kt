/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online

/**
 * 「自动」来源下搜索源赛马的取舍：先到先得，逐字优先。最先返回歌词的来源先被选中；
 * 选中的是逐行时，之后最先返回的逐字歌词替换它（不论评分）；同为逐字或同为逐行时，匹配评分更高的替换它，
 * 同分保留先到的。
 */
internal class SearchSourceRaceSelection<T>(
    private val wordTimed: (T) -> Boolean,
    private val score: (T) -> Int,
) {
    var selected: T? = null
        private set

    /** [candidate] 成为新的选择时返回 true。 */
    fun offer(candidate: T): Boolean {
        val current = selected
        val replaces = when {
            current == null -> true
            wordTimed(candidate) != wordTimed(current) -> wordTimed(candidate)
            else -> score(candidate) > score(current)
        }
        if (replaces) selected = candidate
        return replaces
    }
}

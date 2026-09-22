package com.juren233.hyperlyricsenhanced.common.lyric

import com.juren233.hyperlyricsenhanced.common.RootConstants

object AdjacentTranslationPolicy {
    const val LYRIC_CONTENT_MODE = 7

    fun isEligible(lyricMode: Int, leftMode: Int, rightMode: Int): Boolean =
        lyricMode == RootConstants.HOOK_LYRIC_MODE_SINGLE_SIDE &&
            ((leftMode == LYRIC_CONTENT_MODE) xor (rightMode == LYRIC_CONTENT_MODE))

    fun targetIsLeft(leftMode: Int, rightMode: Int): Boolean? = when {
        leftMode == LYRIC_CONTENT_MODE && rightMode != LYRIC_CONTENT_MODE -> false
        rightMode == LYRIC_CONTENT_MODE && leftMode != LYRIC_CONTENT_MODE -> true
        else -> null
    }
}

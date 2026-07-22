package com.juren233.hyperlyricsenhanced.common.lyric

object AdjacentTranslationPolicy {
    const val VERBATIM_LYRIC_MODE = 0
    const val LYRIC_CONTENT_MODE = 7

    fun isEligible(lyricMode: Int, leftMode: Int, rightMode: Int): Boolean =
        lyricMode == VERBATIM_LYRIC_MODE &&
            ((leftMode == LYRIC_CONTENT_MODE) xor (rightMode == LYRIC_CONTENT_MODE))

    fun targetIsLeft(leftMode: Int, rightMode: Int): Boolean? = when {
        leftMode == LYRIC_CONTENT_MODE && rightMode != LYRIC_CONTENT_MODE -> false
        rightMode == LYRIC_CONTENT_MODE && leftMode != LYRIC_CONTENT_MODE -> true
        else -> null
    }
}

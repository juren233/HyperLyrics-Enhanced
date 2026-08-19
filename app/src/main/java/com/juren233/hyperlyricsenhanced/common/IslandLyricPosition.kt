/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

internal object IslandLyricPosition {
    fun resolve(storedPosition: Int?, legacyCenterEnabled: Boolean): Int {
        return storedPosition
            ?.takeIf {
                it in RootConstants.ISLAND_LYRIC_POSITION_DEFAULT..
                    RootConstants.ISLAND_LYRIC_POSITION_RIGHT
            }
            ?: if (legacyCenterEnabled) {
                RootConstants.ISLAND_LYRIC_POSITION_CENTER
            } else {
                RootConstants.DEFAULT_HOOK_LYRIC_POSITION
            }
    }

    fun centers(position: Int): Boolean =
        position == RootConstants.ISLAND_LYRIC_POSITION_CENTER

    fun alignsRight(position: Int): Boolean =
        position == RootConstants.ISLAND_LYRIC_POSITION_RIGHT

    fun supportsGroupVocalCentering(
        lyricMode: Int,
        leftContent: Int,
        rightContent: Int
    ): Boolean = lyricMode == RootConstants.DEFAULT_HOOK_LYRIC_MODE &&
        (leftContent == 7 || rightContent == 7)

    fun resolveSide(
        storedSidePosition: Int?,
        legacyGlobalPosition: Int?,
        legacyCenterEnabled: Boolean
    ): Int = resolve(
        storedPosition = storedSidePosition ?: legacyGlobalPosition,
        legacyCenterEnabled = legacyCenterEnabled
    )
}

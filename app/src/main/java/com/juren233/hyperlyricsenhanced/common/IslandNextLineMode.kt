/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.common

/**
 * 「显示下一句歌词」开关（[RootConstants.KEY_HOOK_NEXT_LYRIC_LINE]）所有歌词模式共用；
 * 全岛另有「全岛滚动时下一句歌词」位置（[RootConstants.KEY_HOOK_ISLAND_FULL_NEXT_LINE_POSITION]：第二行 / 右侧）。
 * 2026-11-19 前全岛使用独立的三档键（[RootConstants.KEY_HOOK_ISLAND_NEXT_LINE_MODE]：不显示 / 第二行 / 右侧），
 * 现为旧键，只作迁移与位置的兼容输入。
 */
object IslandNextLineMode {
    const val UNSPECIFIED = -1
    /** 仅旧三档键使用。 */
    const val OFF = 0
    const val SECOND_LINE = 1
    const val RIGHT = 2
    const val DEFAULT_FULL_ISLAND_POSITION = SECOND_LINE

    /** 全岛位置：新键有效值优先；否则由旧三档键推出（右侧 → 右侧，其余 → 第二行）。 */
    fun resolvePosition(storedPosition: Int, legacyMode: Int): Int = when {
        storedPosition == SECOND_LINE || storedPosition == RIGHT -> storedPosition
        legacyMode == RIGHT -> RIGHT
        else -> DEFAULT_FULL_ISLAND_POSITION
    }

    /** 旧三档键的迁移写入；null 表示该键不写。 */
    data class LegacyMigration(val nextLyricLine: Boolean?, val fullIslandPosition: Int?)

    /**
     * 旧三档键迁移：位置键不存在时由旧值推出位置；竖屏是全岛时，旧三档才是当时生效的设置，
     * 开关改为旧值是否开启，保持升级前全岛的表现；其他模式本就由开关决定，不改。位置键已存在视为已迁移。
     */
    fun migrateLegacy(
        legacyMode: Int,
        nextLyricLine: Boolean,
        portraitMode: Int,
        hasPosition: Boolean,
    ): LegacyMigration {
        if (hasPosition) return LegacyMigration(null, null)
        val enabled = legacyMode == SECOND_LINE || legacyMode == RIGHT
        val switch = if (portraitMode == RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND && enabled != nextLyricLine) {
            enabled
        } else null
        return LegacyMigration(switch, resolvePosition(UNSPECIFIED, legacyMode))
    }
}

/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.common

/**
 * 超级岛「单侧长度」（[RootConstants.KEY_HOOK_ISLAND_SIDE_LENGTH]，dp，0 为不限）。
 *
 * 它是单侧的总预算，含封面（左）或音频律动（右）占的位置：文字上限 = 预算 − 该侧图标当前被原生预留的宽度，
 * 图标不占位时预留为 0，那一侧的文字自然更长（换算见 `IslandIconReservation`）。
 *
 * 2026-11-19 前左右各有一个只约束文字的上限（[RootConstants.KEY_HOOK_ISLAND_LEFT_CONTENT_MAX_WIDTH]、
 * [RootConstants.KEY_HOOK_ISLAND_RIGHT_CONTENT_MAX_WIDTH]，默认 80 / 100），现为旧键，只作迁移输入。
 */
object IslandSideLength {
    const val UNLIMITED = 0
    const val MAX = 500

    /**
     * 迁移时给旧文字上限补上的图标位置（dp）：取原生大图标尺寸 `island_big_icon_size` = 26dp
     * （MIUISystemUIPlugin OS4.0.0.6 的 dimens）。它只用来给新值定初值，用户可随时调整；
     * 运行时的换算读真实预留宽度，不使用这个常数。
     */
    const val LEGACY_ICON_ALLOWANCE_DP = 26

    /** 与旧默认（左 80 / 右 100）等价的初值。 */
    const val DEFAULT = 100 + LEGACY_ICON_ALLOWANCE_DP

    fun coerce(value: Int): Int = value.coerceIn(UNLIMITED, MAX)

    /**
     * 旧左右文字上限 → 单侧长度：取较大的一侧再补上图标位置，保证原来较长的一侧不被截短。
     * 某一侧为 0（不限）时只看另一侧；两侧都为 0 时仍是不限。
     */
    fun fromLegacy(left: Int, right: Int): Int {
        val widest = maxOf(left, right)
        if (widest <= 0) return UNLIMITED
        return coerce(widest + LEGACY_ICON_ALLOWANCE_DP)
    }

    /**
     * 有效单侧长度：新键有值时用新键；否则由旧键推出（旧键只设了一侧时，另一侧按旧默认）；
     * 都没有时用默认。
     */
    fun resolve(stored: Int?, legacyLeft: Int?, legacyRight: Int?): Int = when {
        stored != null -> coerce(stored)
        legacyLeft != null || legacyRight != null -> fromLegacy(
            legacyLeft ?: RootConstants.DEFAULT_HOOK_ISLAND_LEFT_CONTENT_MAX_WIDTH,
            legacyRight ?: RootConstants.DEFAULT_HOOK_ISLAND_RIGHT_CONTENT_MAX_WIDTH,
        )
        else -> DEFAULT
    }
}

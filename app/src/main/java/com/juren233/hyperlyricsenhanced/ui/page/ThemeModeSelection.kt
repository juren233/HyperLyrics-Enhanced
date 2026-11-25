/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.ui.page

/**
 * 设置页「外观」把主题拆成「深浅模式」与「莫奈取色」两项显示，存储仍是原来的
 * [com.juren233.hyperlyricsenhanced.common.UIConstants.KEY_THEME_MODE] 单值 0–5，不迁移：
 * 0 跟随系统、1 浅色、2 深色，3–5 依次为它们的莫奈版本。
 * 超出 0–5 的旧值按主题实际生效的样子显示，即跟随系统、不取色（与 ThemeUtils 的兜底一致）。
 */
internal object ThemeModeSelection {
    const val BRIGHTNESS_SYSTEM = 0
    const val BRIGHTNESS_LIGHT = 1
    const val BRIGHTNESS_DARK = 2

    private const val MONET_OFFSET = 3
    private val VALID_RANGE = 0..5

    /** 深浅模式下标：0 跟随系统、1 浅色、2 深色。 */
    fun brightnessOf(themeMode: Int): Int =
        if (themeMode in VALID_RANGE) themeMode % MONET_OFFSET else BRIGHTNESS_SYSTEM

    fun monetOf(themeMode: Int): Boolean = themeMode in VALID_RANGE && themeMode >= MONET_OFFSET

    /** 两项合回存储值。 */
    fun combine(brightness: Int, monet: Boolean): Int =
        brightness.coerceIn(BRIGHTNESS_SYSTEM, BRIGHTNESS_DARK) + if (monet) MONET_OFFSET else 0
}

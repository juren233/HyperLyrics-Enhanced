/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.common

import android.content.SharedPreferences

/**
 * 竖屏歌词模式与「横屏状态」的唯一解析处：SystemUI 运行时取生效模式、设置界面判断哪些模式的设置要显示，都经由这里。
 * 横屏只换模式，其余设置全部沿用同一份（用户 2026-11-19 指定）。
 */
object LyricModeResolver {
    private val MODES = RootConstants.HOOK_LYRIC_MODE_SINGLE_SIDE..RootConstants.HOOK_LYRIC_MODE_SEPARATED

    /** 横屏状态是否指定了具体模式（否则跟随竖屏）。 */
    fun landscapeOverride(landscapeMode: Int): Int? = landscapeMode.takeIf { it in MODES }

    fun effective(portraitMode: Int, landscapeMode: Int, isLandscape: Boolean): Int =
        if (isLandscape) landscapeOverride(landscapeMode) ?: portraitMode else portraitMode

    fun usage(prefs: SharedPreferences): LyricModeUsage = LyricModeUsage(
        portraitMode = prefs.getInt(RootConstants.KEY_HOOK_LYRIC_MODE, RootConstants.DEFAULT_HOOK_LYRIC_MODE),
        landscapeMode = prefs.getInt(
            RootConstants.KEY_HOOK_LYRIC_MODE_LANDSCAPE,
            RootConstants.DEFAULT_HOOK_LYRIC_MODE_LANDSCAPE,
        ),
    )
}

/**
 * 竖屏与横屏各自生效的歌词模式。设置界面据此决定某项设置是否显示，以及是否只对其中一个方向生效
 * （副标题开头标「竖屏模式 · 」/「横屏模式 · 」，用户 2026-11-19 要求）。
 */
data class LyricModeUsage(val portraitMode: Int, val landscapeMode: Int) {
    enum class Scope { BOTH, PORTRAIT, LANDSCAPE }

    private val landscapeEffective = LyricModeResolver.effective(portraitMode, landscapeMode, isLandscape = true)

    /** [applies] 判断某项设置在给定模式下是否生效；两个方向都不生效时返回 null（该设置不显示）。 */
    fun scope(applies: (mode: Int) -> Boolean): Scope? {
        val portrait = applies(portraitMode)
        val landscape = applies(landscapeEffective)
        return when {
            portrait && landscape -> Scope.BOTH
            portrait -> Scope.PORTRAIT
            landscape -> Scope.LANDSCAPE
            else -> null
        }
    }

    fun scopeOf(vararg modes: Int): Scope? = scope { it in modes }

    fun uses(vararg modes: Int): Boolean = scopeOf(*modes) != null
}

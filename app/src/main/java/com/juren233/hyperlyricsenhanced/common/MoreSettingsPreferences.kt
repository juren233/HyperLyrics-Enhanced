/*
 * Copyright 2026 juren233
 * Licensed under the GNU General Public License v3.0
 */

package com.juren233.hyperlyricsenhanced.common

import android.content.SharedPreferences

/**
 * 「更多」子页（手势与界面、高级）各开关的读取入口。
 *
 * 这些键只在用户拨动开关时写入，没拨过的用户读到的是这里的默认值；
 * 已经写入的值（包括从旧版本升级保留下来的）原样读回，不做迁移。
 */
object MoreSettingsPreferences {
    fun predictiveBackGesture(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(UIConstants.KEY_PREDICTIVE_BACK_GESTURE, UIConstants.DEFAULT_PREDICTIVE_BACK_GESTURE)

    fun swipeBackGesture(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(UIConstants.KEY_SWIPE_BACK_GESTURE, UIConstants.DEFAULT_SWIPE_BACK_GESTURE)

    fun parallelWindowUi(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(UIConstants.KEY_PARALLEL_WINDOW_UI, UIConstants.DEFAULT_PARALLEL_WINDOW_UI)

    fun topBarProgressiveBlur(prefs: SharedPreferences): Boolean =
        TopBarProgressiveBlurPreference.read(prefs)

    fun floatingNavBar(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(UIConstants.KEY_FLOATING_NAV_BAR, UIConstants.DEFAULT_FLOATING_NAV_BAR)

    fun excludeFromRecents(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(UIConstants.KEY_EXCLUDE_FROM_RECENTS, UIConstants.DEFAULT_EXCLUDE_FROM_RECENTS)

    fun hideAppIcon(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(UIConstants.KEY_HIDE_APP_ICON, UIConstants.DEFAULT_HIDE_APP_ICON)
}

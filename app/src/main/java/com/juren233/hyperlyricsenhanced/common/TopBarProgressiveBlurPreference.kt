/*
 * Copyright 2026 juren233
 * Licensed under the GNU General Public License v3.0
 */

package com.juren233.hyperlyricsenhanced.common

import android.content.SharedPreferences

/** Keeps the old three-option setting readable until users next change the switch. */
object TopBarProgressiveBlurPreference {
    fun read(prefs: SharedPreferences): Boolean {
        if (prefs.contains(UIConstants.KEY_TOP_BAR_PROGRESSIVE_BLUR)) {
            return prefs.getBoolean(
                UIConstants.KEY_TOP_BAR_PROGRESSIVE_BLUR,
                UIConstants.DEFAULT_TOP_BAR_PROGRESSIVE_BLUR,
            )
        }
        if (prefs.contains(UIConstants.KEY_TOP_BAR_PROGRESSIVE_BLUR_MODE)) {
            return runCatching {
                prefs.getInt(UIConstants.KEY_TOP_BAR_PROGRESSIVE_BLUR_MODE, 1) != 0
            }.getOrDefault(UIConstants.DEFAULT_TOP_BAR_PROGRESSIVE_BLUR)
        }
        return UIConstants.DEFAULT_TOP_BAR_PROGRESSIVE_BLUR
    }
}

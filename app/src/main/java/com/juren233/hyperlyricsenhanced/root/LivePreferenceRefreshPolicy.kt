/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root

import com.juren233.hyperlyricsenhanced.common.RootConstants

/** Preference keys that require an immediate SystemUI runtime refresh after app-side changes. */
internal object LivePreferenceRefreshPolicy {
    private val keys = setOf(
        RootConstants.KEY_HOOK_LYRIC_MODE,
        RootConstants.KEY_HOOK_ISLAND_CONTENT_LEFT,
        RootConstants.KEY_HOOK_ISLAND_CONTENT_RIGHT,
        RootConstants.KEY_HOOK_ISLAND_LEFT_LYRIC_POSITION,
        RootConstants.KEY_HOOK_ISLAND_RIGHT_LYRIC_POSITION,
        RootConstants.KEY_HOOK_CENTER_LYRIC,
        RootConstants.KEY_HOOK_CENTER_GROUP_VOCALS,
        RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_COLOR,
        RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_GRADIENT,
        RootConstants.KEY_HOOK_CUSTOM_TEXT_COLOR_ENABLED,
        RootConstants.KEY_HOOK_CUSTOM_TEXT_COLOR,
        RootConstants.KEY_HOOK_MONET_TEXT_COLOR,
        RootConstants.KEY_HOOK_ISLAND_PROGRESS_COLOR_MODE,
        RootConstants.KEY_HOOK_ISLAND_PROGRESS_CUSTOM_COLOR,
        RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE_APP_WHITELIST,
        RootConstants.KEY_ACTIVE_MEDIA_SESSION_PACKAGES,
    )

    fun contains(key: String): Boolean = key in keys
}

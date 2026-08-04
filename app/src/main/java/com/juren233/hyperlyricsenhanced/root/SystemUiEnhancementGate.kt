package com.juren233.hyperlyricsenhanced.root

import com.juren233.hyperlyricsenhanced.common.RootConstants

internal object SystemUiEnhancementGate {
    fun isEnabled(): Boolean {
        val entry = HookEntry.instance ?: return false
        return runCatching {
            entry.prefs.getBoolean(
                RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND,
                RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND
            )
        }.getOrDefault(false)
    }

    fun isLyricRuntimeEnabled(): Boolean {
        val entry = HookEntry.instance ?: return false
        return runCatching {
            isEnabled() || entry.prefs.getBoolean(
                RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
                RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS
            ) || entry.prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION
            )
        }.getOrDefault(false)
    }
}

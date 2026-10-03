package com.juren233.hyperlyricsenhanced.root

import com.juren233.hyperlyricsenhanced.common.RootConstants

internal object SystemUiEnhancementGate {
    fun isEnabled(): Boolean {
        val entry = HookEntry.instance ?: return false
        return runCatching {
            entry.prefs.getBoolean(
                RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND,
                RootConstants.DEFAULT_HOOK_ENABLE_HYPER_ISLAND
            )
        }.getOrDefault(false)
    }

    fun isLyricRuntimeEnabled(): Boolean = lyricRuntimeMode() != SystemUiLyricRuntimeMode.DISABLED

    fun lyricRuntimeMode(): SystemUiLyricRuntimeMode {
        val entry = HookEntry.instance ?: return SystemUiLyricRuntimeMode.DISABLED
        return runCatching {
            SystemUiLyricRuntimeMode.resolve(
                hyperIsland = isEnabled(),
                aodLyrics = entry.prefs.getBoolean(
                    RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
                    RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS,
                ),
                dynamicIsland = entry.prefs.getBoolean(
                    RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND,
                    RootConstants.DEFAULT_HOOK_ENABLE_DYNAMIC_ISLAND,
                ),
                nativeAppleTranslation = entry.prefs.getBoolean(
                    RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION,
                    RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION,
                ),
                fillMissingAppleLyrics = entry.prefs.getBoolean(
                    RootConstants.KEY_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS,
                    RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS,
                ),
                lunaBeatWordLyrics = entry.prefs.getBoolean(
                    RootConstants.KEY_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS,
                    RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS,
                ),
            )
        }.getOrDefault(SystemUiLyricRuntimeMode.DISABLED)
    }
}

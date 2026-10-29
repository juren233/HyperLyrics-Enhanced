package com.juren233.hyperlyricsenhanced.root

import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.utils.LyricRuntimeDiagnostics

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
        val entry = HookEntry.instance ?: run {
            LyricRuntimeDiagnostics.record("runtime_mode_resolved") { "mode=DISABLED reason=no_hook_entry_instance" }
            return SystemUiLyricRuntimeMode.DISABLED
        }
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
                lineToWordLyrics = entry.prefs.getBoolean(
                    RootConstants.KEY_HOOK_APPLE_MUSIC_LINE_TO_WORD_LYRICS,
                    RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LINE_TO_WORD_LYRICS,
                ),
            )
        }.onSuccess { mode ->
            LyricRuntimeDiagnostics.record("runtime_mode_resolved") {
                "mode=$mode hyperIsland=${isEnabled()} " +
                    "aod=${entry.prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS, RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS)} " +
                    "dynamicIsland=${entry.prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_DYNAMIC_ISLAND)}"
            }
        }.onFailure { error ->
            LyricRuntimeDiagnostics.record("runtime_mode_resolved") {
                "mode=DISABLED reason=resolve_failed error=${error.javaClass.name}:${error.message}"
            }
        }.getOrDefault(SystemUiLyricRuntimeMode.DISABLED)
    }
}

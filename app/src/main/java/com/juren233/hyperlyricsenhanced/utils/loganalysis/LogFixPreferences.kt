/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.utils.loganalysis

import android.content.SharedPreferences
import androidx.core.content.edit
import com.juren233.hyperlyricsenhanced.common.IslandRealRhythmConfig
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.common.RootConstants

/** 一键处理要改的一项设置，写法与对应设置页完全相同（本地 SharedPreferences + PrefsBridge 同步给系统界面）。 */
internal sealed interface PrefWrite {
    val key: String

    data class IntValue(override val key: String, val value: Int) : PrefWrite
    data class BoolValue(override val key: String, val value: Boolean) : PrefWrite
    data class StringValue(override val key: String, val value: String) : PrefWrite
}

internal object LogFixPreferences {
    /** 一键处理对应的设置改动；不改设置的处理返回空列表。 */
    fun writesFor(action: LogFixAction): List<PrefWrite> = when (action) {
        LogFixAction.ClearCustomFont -> listOf(PrefWrite.StringValue(RootConstants.KEY_HOOK_CUSTOM_FONT_PATH, ""))
        is LogFixAction.SwitchLyricSource -> listOf(PrefWrite.StringValue(RootConstants.KEY_HOOK_LYRIC_SOURCE, action.sourceId))
        is LogFixAction.TurnOffFeature -> when (action.feature) {
            // 与「超级岛 → 内容 → 音频封面样式」选「默认」相同。
            SystemFeature.ISLAND_COVER_STYLE -> listOf(
                PrefWrite.IntValue(RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE, RootConstants.ISLAND_ALBUM_COVER_STYLE_DEFAULT),
            )
            // 与「音频律动封面色」选关闭时的三项写入相同。
            SystemFeature.MUSIC_WAVE_COLOR -> listOf(
                PrefWrite.IntValue(
                    RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR_MODE,
                    RootConstants.ISLAND_MUSIC_WAVE_COLOR_MODE_DISABLED,
                ),
                PrefWrite.BoolValue(RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR, false),
                PrefWrite.BoolValue(RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_GRADIENT, false),
            )
            // 封面样式、隐藏来源标识、隐藏设备切换按钮都靠同一个 Hook，一起恢复默认。
            SystemFeature.NOTIFICATION_MEDIA_COVER -> listOf(
                PrefWrite.IntValue(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_COVER_STYLE,
                    RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_DEFAULT,
                ),
                PrefWrite.BoolValue(RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_HIDE_COVER_SOURCE, false),
                PrefWrite.BoolValue(RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_HIDE_DEVICE_SWITCH, false),
            )
            SystemFeature.NARROW_LATIN_FONT -> listOf(PrefWrite.BoolValue(RootConstants.KEY_HOOK_NARROW_LATIN_FONT, false))
            // Hook 点位失效的功能改为引导反馈，不提供一键关闭。
            SystemFeature.SETTINGS_ENTRY,
            SystemFeature.AOD_LYRICS,
            SystemFeature.ISLAND_EXPANDED_FLOW,
            SystemFeature.NOTIFICATION_MEDIA_CARD,
            SystemFeature.NOTIFICATION_MEDIA_BACKGROUND,
            SystemFeature.ISLAND_GLOW -> emptyList()
        }
        // 与「跟随音乐真实律动」选「不开启」相同。
        LogFixAction.TurnOffRealRhythm -> listOf(
            PrefWrite.IntValue(IslandRealRhythmConfig.KEY_MODE, IslandRealRhythmConfig.MODE_OFF),
        )
        else -> emptyList()
    }

    fun apply(prefs: SharedPreferences, writes: List<PrefWrite>, bridge: (PrefWrite) -> Unit = ::syncToSystemUi) {
        if (writes.isEmpty()) return
        prefs.edit {
            writes.forEach { write ->
                when (write) {
                    is PrefWrite.IntValue -> putInt(write.key, write.value)
                    is PrefWrite.BoolValue -> putBoolean(write.key, write.value)
                    is PrefWrite.StringValue -> putString(write.key, write.value)
                }
            }
        }
        writes.forEach(bridge)
    }

    private fun syncToSystemUi(write: PrefWrite) {
        when (write) {
            is PrefWrite.IntValue -> PrefsBridge.putInt(write.key, write.value)
            is PrefWrite.BoolValue -> PrefsBridge.putBoolean(write.key, write.value)
            is PrefWrite.StringValue -> PrefsBridge.putString(write.key, write.value)
        }
    }
}

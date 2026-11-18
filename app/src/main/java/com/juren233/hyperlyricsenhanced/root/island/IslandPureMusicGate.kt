/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.LyricsAvailability
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.PureMusicKeywords
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer

/**
 * 无歌词音乐期间音乐岛状态：当前歌曲被判为纯音乐（或所有来源都没有歌词）时，按设置交还系统短岛的原播放样式
 * （不注入歌词；封面样式、边缘光效、颜色仍由各自的 Hook 处理），或切到「歌曲信息」（左侧只留封面、右侧显示
 * 歌名与歌手，见 [IslandSlotRuntimeConfig.songInfoDisplay]）。找词期间两者都不生效。
 */
internal object IslandPureMusicGate {
    enum class State { INACTIVE, SYSTEM_COMPACT, SONG_INFO }

    @Volatile
    private var lastState = State.INACTIVE

    private val stateChangedListener: () -> Unit = {
        val state = lastEvaluated()
        if (state != lastState) {
            lastState = state
            IslandMusicWaveColorHooker.refresh()
            BaseIslandRenderer.refreshActiveIsland()
        }
    }

    private var prefsProvider: (() -> SharedPreferences?)? = null

    fun start(provider: () -> SharedPreferences?) {
        prefsProvider = provider
        lastState = lastEvaluated()
        LyriconDataBridge.addSongChangedListener(stateChangedListener)
        LyricsAvailability.addListener(stateChangedListener)
    }

    fun stop() {
        LyriconDataBridge.removeSongChangedListener(stateChangedListener)
        LyricsAvailability.removeListener(stateChangedListener)
        prefsProvider = null
        lastState = State.INACTIVE
    }

    /** 设置变化后重新评估；状态变了才返回 true。 */
    fun onPreferenceChanged(): Boolean {
        val state = lastEvaluated()
        val changed = state != lastState
        lastState = state
        return changed
    }

    private fun lastEvaluated(): State = prefsProvider?.invoke()?.let(::state) ?: State.INACTIVE

    /** 选了「系统短岛」且此刻无歌词：不注入，交还原生岛。 */
    fun isSystemCompactActive(prefs: SharedPreferences): Boolean = state(prefs) == State.SYSTEM_COMPACT

    /** 选了「歌曲信息」且此刻无歌词。 */
    fun isSongInfoActive(prefs: SharedPreferences): Boolean = state(prefs) == State.SONG_INFO

    /** 热路径（原生每秒都会重发岛更新，槽位配置每次取用也会判定），关键词解析按原始串缓存。 */
    fun state(prefs: SharedPreferences): State {
        val mode = IslandRuntimePreferenceReader.getInt(
            prefs,
            RootConstants.KEY_HOOK_ISLAND_PURE_MUSIC_MODE,
            RootConstants.DEFAULT_HOOK_ISLAND_PURE_MUSIC_MODE,
        )
        val target = when (mode) {
            RootConstants.ISLAND_PURE_MUSIC_MODE_SYSTEM_COMPACT -> State.SYSTEM_COMPACT
            RootConstants.ISLAND_PURE_MUSIC_MODE_SONG_INFO -> State.SONG_INFO
            else -> return State.INACTIVE
        }
        // 纯音乐占位文本，或所有来源（含重试）都没有歌词。
        val kw = PureMusicKeywords.of(prefs)
        val result = LyricsAvailability.finalUnavailable || LyriconDataBridge.isPureMusic(kw)
        if (com.juren233.hyperlyricsenhanced.BuildConfig.DEBUG) {
            val signature = "$target|$result|${LyricsAvailability.finalUnavailable}|" +
                "${LyriconDataBridge.versionCounter.get()}|${kw.size}"
            if (signature != lastLoggedSignature) {
                lastLoggedSignature = signature
                com.juren233.hyperlyricsenhanced.root.utils.HookLogger.d(
                    "IslandPureMusicGate",
                    "纯音乐判定: mode=$target, active=$result, finalUnavailable=${LyricsAvailability.finalUnavailable}, " +
                        "${LyriconDataBridge.pureMusicDiagnostic()}, keywords=${kw.size}",
                )
            }
        }
        return if (result) target else State.INACTIVE
    }

    @Volatile
    private var lastLoggedSignature: String? = null
}

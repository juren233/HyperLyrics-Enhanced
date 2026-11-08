/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.common.IslandPureMusicPolicy
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.LyricsAvailability
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer

/**
 * 纯音乐期间音乐岛状态：选择“系统短岛”且当前歌曲被判为纯音乐（或所有来源都没有歌词）时，不注入歌词，
 * 交还系统短岛的原播放样式（封面样式、边缘光效、颜色仍由各自的 Hook 处理）。
 */
internal object IslandPureMusicGate {
    private class KeywordCache(val raw: String?, val seen: String?, val keywords: Set<String>)

    @Volatile
    private var keywordCache: KeywordCache? = null

    @Volatile
    private var lastActive = false

    private val stateChangedListener: () -> Unit = {
        val active = lastEvaluated()
        if (active != lastActive) {
            lastActive = active
            IslandMusicWaveColorHooker.refresh()
            BaseIslandRenderer.refreshActiveIsland()
        }
    }

    private var prefsProvider: (() -> SharedPreferences?)? = null

    fun start(provider: () -> SharedPreferences?) {
        prefsProvider = provider
        lastActive = lastEvaluated()
        LyriconDataBridge.addSongChangedListener(stateChangedListener)
        LyricsAvailability.addListener(stateChangedListener)
    }

    fun stop() {
        LyriconDataBridge.removeSongChangedListener(stateChangedListener)
        LyricsAvailability.removeListener(stateChangedListener)
        prefsProvider = null
        lastActive = false
    }

    /** 设置变化后重新评估；状态变了才返回 true。 */
    fun onPreferenceChanged(): Boolean {
        val active = lastEvaluated()
        val changed = active != lastActive
        lastActive = active
        return changed
    }

    private fun lastEvaluated(): Boolean = prefsProvider?.invoke()?.let(::isActive) ?: false

    /** 热路径（原生每秒都会重发岛更新），关键词解析按原始串缓存。 */
    fun isActive(prefs: SharedPreferences): Boolean {
        val mode = IslandRuntimePreferenceReader.getInt(
            prefs,
            RootConstants.KEY_HOOK_ISLAND_PURE_MUSIC_MODE,
            RootConstants.DEFAULT_HOOK_ISLAND_PURE_MUSIC_MODE,
        )
        if (mode != RootConstants.ISLAND_PURE_MUSIC_MODE_SYSTEM_COMPACT) return false
        // 纯音乐占位文本，或所有来源（含重试）都没有歌词。
        val kw = keywords(prefs)
        val result = LyricsAvailability.finalUnavailable || LyriconDataBridge.isPureMusic(kw)
        if (com.juren233.hyperlyricsenhanced.BuildConfig.DEBUG) {
            val signature = "$result|${LyricsAvailability.finalUnavailable}|${LyriconDataBridge.pureMusicDiagnostic()}"
            if (signature != lastLoggedSignature) {
                lastLoggedSignature = signature
                com.juren233.hyperlyricsenhanced.root.utils.HookLogger.d(
                    "IslandPureMusicGate",
                    "纯音乐判定: active=$result, finalUnavailable=${LyricsAvailability.finalUnavailable}, " +
                        "${LyriconDataBridge.pureMusicDiagnostic()}, keywords=${kw.size}",
                )
            }
        }
        return result
    }

    @Volatile
    private var lastLoggedSignature: String? = null

    private fun keywords(prefs: SharedPreferences): Set<String> {
        val raw = IslandRuntimePreferenceReader.getString(
            prefs,
            RootConstants.KEY_HOOK_ISLAND_PURE_MUSIC_KEYWORDS,
            null,
        )
        val seen = IslandRuntimePreferenceReader.getString(
            prefs,
            RootConstants.KEY_HOOK_ISLAND_PURE_MUSIC_KEYWORDS_SEEN_DEFAULTS,
            null,
        )
        keywordCache?.takeIf { it.raw == raw && it.seen == seen }?.let { return it.keywords }
        val parsed = IslandPureMusicPolicy.effectiveKeywordSet(raw, seen)
        keywordCache = KeywordCache(raw, seen, parsed)
        return parsed
    }
}

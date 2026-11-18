/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.common.IslandPureMusicPolicy
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.island.IslandRuntimePreferenceReader

/**
 * 「纯音乐判定关键词」的生效集合（歌词设置 · 显示）。超级岛「无歌词音乐期间音乐岛状态」与两种 AOD 的
 * 「纯音乐隐藏占位歌词」共用这一份；热路径上按原始串缓存解析结果。
 */
internal object PureMusicKeywords {
    private class Cache(val raw: String?, val seen: String?, val keywords: Set<String>)

    @Volatile
    private var cache: Cache? = null

    fun of(prefs: SharedPreferences): Set<String> {
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
        cache?.takeIf { it.raw == raw && it.seen == seen }?.let { return it.keywords }
        val parsed = IslandPureMusicPolicy.effectiveKeywordSet(raw, seen)
        cache = Cache(raw, seen, parsed)
        return parsed
    }
}

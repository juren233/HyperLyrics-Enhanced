/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceMetadata
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.online.OnlineLyricTargeter
import com.juren233.hyperlyricsenhanced.online.model.Source

/**
 * 应用内 Apple 歌曲的歌词来源检索结果落地（对应正式路径的 [applyFallbackResult]）。
 *
 * 只更新应用内槽位的歌曲，并经 [directBridge] 回传给 Apple Music 插件；绝不调用 sink、
 * [publishAppleSong]、位置轮询、[fallbackIslandSwitch]，也不改动 [publication]。
 * - [requestedSource] 不为空：用户在弹窗里手动选了歌词来源，结果作为补充歌词回传并转为槽位歌曲，
 *   随后按新时间轴重新检索翻译/发音；
 * - [requestedSource] 为空：只为弹窗收集各来源的检索状态（无歌词的歌曲），不应用任何歌词。
 */
internal fun LyriconSource.applyInAppFallbackResult(
    generation: Int,
    baseSong: LocalSong,
    rawOutcome: OnlineLyricTargeter.FetchOutcome,
    requestedSource: Source?,
) {
    val target = inAppApple.target
    val outcome = rawOutcome.stripFullyChineseTranslations()
    val slotSong = inAppApple.song()
    val sameTrack = slotSong != null && isSameTrack(slotSong, baseSong)
    val pendingRequest = target.manual.lyricsRequestToComplete(requestedSource)
    val manualLyricsSourceSwitch = requestedSource != null && pendingRequest?.songId == baseSong.id
    val requestStillCurrent = slotSong != null && acceptsAppleOnlineLyricResult(
        generation = generation,
        currentGeneration = target.fallback.generation(),
        sameTrack = sameTrack,
        currentNativeLyrics = inAppApple.hasNativeLyrics(),
        currentSongHasNativeLyrics = hasAppleNativeLyrics(slotSong),
        manualSourceSwitch = manualLyricsSourceSwitch,
    )
    if (!requestStillCurrent || slotSong == null) {
        diagnostic(
            "Apple Music 应用内歌词检索结果已过期: title=${baseSong.name}, generation=$generation, " +
                "currentGeneration=${target.fallback.generation()}, sameTrack=$sameTrack, " +
                "manual=$manualLyricsSourceSwitch"
        )
        return
    }
    target.fallback.clearJob()
    if (requestedSource == null) {
        publishInAppLyricsSourceStatuses(slotSong, outcome)
        return
    }
    val supplementSong = if (manualLyricsSourceSwitch) {
        buildFallbackSupplement(slotSong, baseSong, outcome).song
    } else {
        null
    }
    if (supplementSong != null) {
        val published = directBridge?.publishMissingLyricsSupplement(supplementSong) == true
        diagnostic(
            "Apple Music 应用内歌词来源切换结果: id=${supplementSong.id}, " +
                "lines=${supplementSong.lyrics.orEmpty().size}, source=${outcome.selectedSource}, " +
                "published=$published"
        )
        inAppApple.acceptSupplement(supplementSong, selection = null)
        inAppApple.recordPublished(null)
    } else {
        diagnostic("Apple Music 应用内歌词来源未命中，保留当前歌词: id=${baseSong.id}, requested=$requestedSource")
    }
    // 命中则按补充歌曲确认所选来源；回传切换结果（命中与否都要给插件一个确定的结局）。
    completePendingLyricsSourceRequest(supplementSong, requestedSource, target)
    if (supplementSong != null && wantsSourceControlSearch(supplementSong, target)) {
        // 正文换成新来源后，翻译/发音必须按新时间轴重新匹配（与正式路径同一规则）。
        scheduleOnlineTranslation(supplementSong, target)
    }
}

/**
 * 无歌词的应用内歌曲没有可匹配的翻译基准，各来源的歌词检索状态经在线内容通道回传，
 * 供弹窗显示每个来源有没有歌词；这份载荷不带任何歌词行。
 */
private fun LyriconSource.publishInAppLyricsSourceStatuses(
    slotSong: LocalSong,
    outcome: OnlineLyricTargeter.FetchOutcome,
) {
    val encoded = AppleMissingLyricsSourceMetadata.encodeStatuses(outcome.sourceStatuses) ?: return
    val statusSong = slotSong.copy(
        lyrics = emptyList(),
        metadata = lyricMetadataOf(LyricMetadataKeys.ONLINE_LYRICS_SOURCE_STATUSES to encoded),
    )
    val published = directBridge?.publishOnlineTranslation(statusSong) == true
    diagnostic(
        "Apple Music 应用内歌词来源状态已回传: id=${slotSong.id}, " +
            "statuses=${outcome.sourceStatuses.joinToString("+") { it.source + ":" + it.found }}, " +
            "published=$published"
    )
}

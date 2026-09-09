/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceInfo
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceMetadata
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceStatus
import com.juren233.hyperlyricsenhanced.common.lyric.ApplePronunciationVisibilityPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.ChineseLyricsPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineTranslationContentPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.TraditionalLyricsSimplifier
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.common.media.NextTrackMetadataCache
import com.juren233.hyperlyricsenhanced.lyric.LrcLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSink
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSource
import com.juren233.hyperlyricsenhanced.online.OnlineLyricTargeter
import com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.online.source.lunabeat.LunaBeatLookupResult
import com.juren233.hyperlyricsenhanced.online.source.lunabeat.LunaBeatTtmlRepository
import com.juren233.hyperlyricsenhanced.online.utils.ChineseUtils
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.MediaCardDiagnosticLogger
import io.github.proify.lyricon.amprovider.xposed.AppleDirectBridgeContract
import io.github.proify.lyricon.amprovider.xposed.AppleSourceSwitchPerformanceDiagnostics
import io.github.proify.lyricon.lyric.model.Song as LyriconSong
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.ConnectionListener
import io.github.proify.lyricon.subscriber.LyriconFactory
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.subscriber.ProviderInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

internal fun LyriconSource.handleAppleSong(incomingSong: LocalSong?) {
    val previousSong = currentAppleSong
    if (incomingSong != null && !isMissingLyricsSupplement(incomingSong)) {
        publication.rememberNative(incomingSong)
    }
    val sameTrack = previousSong != null && incomingSong != null &&
        isSameTrack(previousSong, incomingSong)
    val authoritativeLyricsSource = confirmedLyricsSourceSelection
        ?.takeIf { selection ->
            sameTrack &&
                selection.songId == previousSong.id &&
                selection.songId == incomingSong.id
        }
        ?.source
    val mergedSong = mergeMissingLyricsSupplementMetadata(
        previousSong = previousSong,
        incomingSong = incomingSong,
        sameTrack = sameTrack,
        authoritativeSource = authoritativeLyricsSource,
    )
    // 正文与来源按合并结果保留;标题/歌手仍跟随最新回调,避免保留正文时丢失晚到的显示元数据。
    val song = mergeRetainedSongDisplayMetadata(
        mergedSong = mergedSong,
        previousSong = previousSong,
        incomingSong = incomingSong,
    )
    val authoritativeNativeTransition = sameTrack &&
        isMissingLyricsSupplement(previousSong) &&
        hasConfirmedAppleNativeLyrics(song)
    if (authoritativeNativeTransition) {
        diagnostic(
            "Apple Music 原生歌词权威接管: id=${song?.id}, " +
                "previousLines=${previousSong.lyrics.orEmpty().size}, " +
                "nativeLines=${song?.lyrics.orEmpty().size}"
        )
    }
    if (mergedSong === previousSong && incomingSong !== previousSong && authoritativeLyricsSource != null) {
        diagnostic(
            "忽略过期 Apple Music 歌词来源回传: id=${previousSong?.id}, " +
                "authoritative=$authoritativeLyricsSource, incoming=" +
                incomingSong?.metadata
                    ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE)
        )
    }
    diagnostic(
        "Apple Music 歌曲入口: id=${song?.id}, title=${song?.name}, " +
            "artist=${song?.artist}, duration=${song?.duration}, " +
            "lyrics=${song?.lyrics.orEmpty().size}, supplement=${isMissingLyricsSupplement(song)}, " +
            "onlineEnabled=${isOnlineTranslationEnabledFor(LyriconSource.APPLE_MUSIC_PACKAGE)}"
    )
    val preservesCurrentLyrics = previousSong != null && song != null &&
        AppleSongUpdatePolicy.shouldPreserveCurrentLyrics(previousSong, song, sameTrack)
    if (preservesCurrentLyrics) {
        refreshRetainedAppleMetadata(song)
        debug("忽略同一首歌的空歌词降级: title=${song.name}")
        return
    }
    val originalMetadataChanged = sameTrack &&
        AppleOnlineTranslationRequestPolicy.originalMetadataChanged(previousSong, song)
    val repeatedEmptySong = sameTrack && song != null && !originalMetadataChanged &&
        song.lyrics.isNullOrEmpty() &&
        (fallbackSongActive || fallbackDelayRunnable != null || fallbackJob?.isActive == true)
    if (repeatedEmptySong) {
        refreshRetainedAppleMetadata(song)
        publication.acceptAppleInput(song, false)
        debug("忽略同一首歌的重复空歌词占位: title=${song.name}")
        return
    }
    // 原生歌词与无歌词补充共用同一套在线翻译匹配任务。同一首歌的后续
    // Apple 回调只更新基准歌词，不能取消正在进行中的翻译任务；否则保留的
    // attempt key 会让兜底结果里的重新调度被误判为重复请求。
    val repeatedLyricsNeedingEnrichment = shouldKeepRunningAppleEnrichment(
        sameTrack = sameTrack,
        authoritativeNativeTransition = authoritativeNativeTransition,
        hasLyrics = !song?.lyrics.isNullOrEmpty(),
        needsEnrichment = needsOnlineEnrichment(song),
        originalMetadataChanged = originalMetadataChanged,
        enrichmentRunning =
            onlineTranslationRunning || onlineTranslationResultReady || onlineMatchedTranslationActive,
    )
    if (repeatedLyricsNeedingEnrichment) {
        refreshRetainedAppleMetadata(song)
        publication.acceptAppleInput(song, hasAppleNativeLyrics(song))
        debug("忽略同一首歌的重复待补全歌词: title=${song?.name}")
        return
    }
    cancelFallback(clearAppleSong = false, reason = "apple_song_updated")
    val incomingHasTranslation = hasTranslation(song)
    val incomingNeedsEnrichment = needsOnlineEnrichment(song)
    cancelOnlineTranslation(
        clearAttempt = shouldClearAppleOnlineTranslationAttempt(
            sameTrack = sameTrack,
            authoritativeNativeTransition = authoritativeNativeTransition,
            needsEnrichment = incomingNeedsEnrichment,
            originalMetadataChanged = originalMetadataChanged,
        ),
        clearMatched = true,
        reason = "apple_song_updated"
    )
    publication.acceptAppleInput(song, hasAppleNativeLyrics(song))
    if (!sameTrack) {
        appleSongGeneration += 1
        appleMediaPositionReference = null
        appleDirectPositionReference = null
        lastAdjustedPosition = 0L
        originalMetadataRequestKey = null
        temporaryTranslationSource = null
        temporaryPronunciationSource = null
        pendingTranslationSourceRequest = null
        pendingPronunciationSourceRequest = null
        pendingLyricsSourceRequest = null
        publication.beginAppleTrack(incomingSong?.takeUnless(::isMissingLyricsSupplement))
        refreshAppleMediaPositionReference()
    }
    val originalMetadataPlan = AppleOnlineTranslationRequestPolicy.originalMetadataLookupPlan(
        song != null && shouldRequestOriginalMetadataForOnlineLookup(song)
    )
    pronunciationDiagnostic(
        "stage=request_entry_gate, id=${song?.id}, generation=$appleSongGeneration, " +
            "prefsPresent=${prefs != null}, matchingEnabled=${isAppleTranslationEnrichmentEnabled()}, " +
            "lyrics=${song?.lyrics.orEmpty().size}, needsEnrichment=${needsOnlineEnrichment(song)}, " +
            "titlePresent=${!song?.name.isNullOrBlank()}, " +
            "requestOriginal=${originalMetadataPlan.requestOriginalMetadata}, " +
            "waitOriginal=${originalMetadataPlan.waitForResult}, sameTrack=$sameTrack, " +
            "originalMetadataChanged=$originalMetadataChanged"
    )
    if (song != null && originalMetadataPlan.requestOriginalMetadata) {
        requestOriginalMetadata(song, "setting_enabled")
    }

    runCatching {
        publishAppleSong(song, restorePosition = sameTrack)
    }.onFailure {
        debugError("Apple Music 歌曲发布失败: title=${song?.name}", it)
    }

    if (
        song != null &&
        !isMissingLyricsSupplement(song) &&
        isLunaBeatWordLyricsEnabled()
    ) {
        scheduleFallback(
            baseSong = song,
            delayMs = 0L,
            preferredSourceOverride = Source.LB,
            strictSource = hasAppleNativeLyrics(song) || !isFillMissingLyricsEnabled(),
        )
    } else if (
        song != null &&
        needsMissingLyricsSourceRecovery(song) &&
        (
            isOnlineTranslationEnabledFor(LyriconSource.APPLE_MUSIC_PACKAGE) ||
                isFillMissingLyricsEnabled()
            )
    ) {
        HookLogger.i(
            LyriconSource.TAG,
            "Apple Music 原生歌词未返回，立即预取在线候选: title=${song.name}"
        )
        // 候选检索可以与 Apple 原生请求并行；Apple 进程内的 takeover gate
        // 仍会在原生状态未确认前禁止呈现，因此这里不再额外等待 5 秒。
        scheduleFallback(song, 0L)
    }
    if (
        song != null &&
        !originalMetadataPlan.waitForResult &&
        hasAppleLyricsForOnlineEnrichment(
            song = song,
            confirmedNativeLyrics = hasAppleNativeLyrics(song),
        ) &&
        incomingNeedsEnrichment &&
        isAppleTranslationEnrichmentEnabled()
    ) {
        HookLogger.i(
            LyriconSource.TAG,
            "Apple Music 歌词待补全: title=${song.name}, " +
                "supplement=${isMissingLyricsSupplement(song)}, " +
                "lines=${song.lyrics.orEmpty().size}, " +
                "hasTranslation=$incomingHasTranslation"
        )
        if (originalMetadataChanged) {
            HookLogger.i(
                LyriconSource.TAG,
                "Apple Music 原名已更新，重新匹配在线翻译: " +
                    "title=${song.name}, originalTitle=${song.metadata?.getString(LyricMetadataKeys.APPLE_ORIGINAL_TITLE)}"
            )
        }
        scheduleOnlineTranslation(song)
    }
}


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
import com.juren233.hyperlyricsenhanced.online.source.lunabeat.LunaBeatLookupResult
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSink
import com.juren233.hyperlyricsenhanced.lyric.source.LyricSource
import com.juren233.hyperlyricsenhanced.online.OnlineLyricTargeter
import com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
import com.juren233.hyperlyricsenhanced.online.model.Source
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

internal fun LyriconSource.scheduleFallback(
    baseSong: LocalSong,
    delayMs: Long,
    preferredSourceOverride: Source? = null,
    strictSource: Boolean = false,
) {
    val sourceSwitchRequest = activeSourceSwitchTraceRequest(baseSong.id)
        ?.takeIf { request ->
            request.contentType == "lyrics" &&
                preferredSourceOverride == request.requestedSource
        }
    val fallbackEnabled = isOnlineTranslationEnabledFor(LyriconSource.APPLE_MUSIC_PACKAGE)
    val supplementEnabled = isFillMissingLyricsEnabled() || isLunaBeatWordLyricsEnabled()
    val lunaBeatRequested = preferredSourceOverride == Source.LB ||
        (preferredSourceOverride == null && isLunaBeatWordLyricsEnabled())
    if (
        (baseSong.name.isNullOrBlank() && !lunaBeatRequested) ||
        (!fallbackEnabled && !supplementEnabled)
    ) {
        return
    }
    val configuredSources = OnlineTranslationSourcePreferences.orderedSources(prefs)
    if (configuredSources.isEmpty() && !lunaBeatRequested) return
    val previousGeneration = fallbackGeneration
    val previousJobActive = fallbackJob?.isActive == true
    val previousDelayPending = fallbackDelayRunnable != null
    fallbackGeneration += 1
    val generation = fallbackGeneration
    fallbackDelayRunnable?.let(mainHandler::removeCallbacks)
    fallbackDelayRunnable = null
    fallbackJob?.cancel()
    fallbackJob = null
    sourceSwitchCoreStage(
        request = sourceSwitchRequest,
        stage = "fallback_scheduled",
        details = "generation=$previousGeneration->$generation,delayMs=$delayMs," +
            "strict=$strictSource,preferred=${preferredSourceOverride ?: "none"}," +
            "order=${configuredSources.joinToString("+")}," +
            "cancelledJobActive=$previousJobActive," +
            "cancelledDelayPending=$previousDelayPending",
    )

    val delayedSearch = Runnable {
        if (generation != fallbackGeneration) {
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "fallback_delay_abandoned",
                details = "generation=$generation,currentGeneration=$fallbackGeneration",
            )
            return@Runnable
        }
        fallbackDelayRunnable = null
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "fallback_worker_launching",
            details = "generation=$generation",
        )
        val application = app
        if (application == null) {
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "fallback_worker_abandoned",
                details = "generation=$generation,reason=application_unavailable",
            )
            diagnostic(
                "Apple Music 在线兜底无法启动: reason=application_unavailable, " +
                    "title=${baseSong.name}"
            )
            return@Runnable
        }
        fallbackJob = fallbackScope.launch {
            val mutexWaitStartedAtNanos = SystemClock.elapsedRealtimeNanos()
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "fallback_mutex_wait_started",
                details = "generation=$generation",
            )
            try {
                fallbackRequestMutex.withLock {
                    sourceSwitchCoreStage(
                        request = sourceSwitchRequest,
                        stage = "fallback_mutex_acquired",
                        details = "generation=$generation,waitMs=" +
                            ((SystemClock.elapsedRealtimeNanos() - mutexWaitStartedAtNanos) /
                                1_000_000.0),
                    )
                    if (generation != fallbackGeneration) {
                        sourceSwitchCoreStage(
                            request = sourceSwitchRequest,
                            stage = "fallback_search_abandoned",
                            details = "generation=$generation," +
                                "currentGeneration=$fallbackGeneration",
                        )
                        return@withLock
                    }
                    diagnostic(
                        "Apple Music 在线兜底开始: title=${baseSong.name}, " +
                            "artist=${baseSong.artist}, order=${configuredSources.joinToString("+")}"
                    )
                    val searchStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                    sourceSwitchCoreStage(
                        request = sourceSwitchRequest,
                        stage = "fallback_search_started",
                        details = "generation=$generation," +
                            "source=${preferredSourceOverride ?: "automatic"}",
                    )
                    val outcome = fetchAppleLyricsOutcome(
                        application = application,
                        baseSong = baseSong,
                        configuredSources = configuredSources,
                        preferredSourceOverride = preferredSourceOverride,
                        strictSource = strictSource,
                    )
                    sourceSwitchCoreStage(
                        request = sourceSwitchRequest,
                        stage = "fallback_search_finished",
                        details = "generation=$generation,elapsedMs=" +
                            ((SystemClock.elapsedRealtimeNanos() - searchStartedAtNanos) /
                                1_000_000.0) +
                            ",selected=${outcome.selectedSource ?: "none"}," +
                            "lines=${outcome.lines?.size ?: 0}," +
                            "wordLines=${outcome.wordLines?.size ?: 0}," +
                            "statuses=${outcome.sourceStatuses.joinToString("+") {
                                it.source + ":" + it.found
                            }}",
                    )
                    val applyPostedAtNanos = SystemClock.elapsedRealtimeNanos()
                    mainHandler.post {
                        val applyStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                        sourceSwitchCoreStage(
                            request = sourceSwitchRequest,
                            stage = "fallback_apply_main_started",
                            details = "generation=$generation,queueWaitMs=" +
                                ((applyStartedAtNanos - applyPostedAtNanos) / 1_000_000.0),
                        )
                        try {
                            applyFallbackResult(
                                generation = generation,
                                baseSong = baseSong,
                                outcome = outcome,
                                application = application,
                                fallbackEnabled = fallbackEnabled,
                                requestedSource = preferredSourceOverride,
                            )
                        } finally {
                            sourceSwitchCoreStage(
                                request = sourceSwitchRequest,
                                stage = "fallback_apply_main_finished",
                                details = "generation=$generation,elapsedMs=" +
                                    ((SystemClock.elapsedRealtimeNanos() - applyStartedAtNanos) /
                                        1_000_000.0),
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                sourceSwitchCoreStage(
                    request = sourceSwitchRequest,
                    stage = "fallback_worker_cancelled",
                    details = "generation=$generation,currentGeneration=$fallbackGeneration",
                )
                throw e
            } catch (e: Exception) {
                sourceSwitchCoreStage(
                    request = sourceSwitchRequest,
                    stage = "fallback_worker_failed",
                    details = "generation=$generation,error=${e.javaClass.simpleName}",
                )
                debugError("Apple Music 在线兜底失败: title=${baseSong.name}", e)
            }
        }
    }
    fallbackDelayRunnable = delayedSearch
    diagnostic(
        "Apple Music 在线兜底已调度: title=${baseSong.name}, " +
            "delayMs=$delayMs, generation=$generation"
    )
    sourceSwitchCoreStage(
        request = sourceSwitchRequest,
        stage = "fallback_delay_posted",
        details = "generation=$generation,delayMs=$delayMs",
    )
    mainHandler.postDelayed(delayedSearch, delayMs)
}

internal suspend fun LyriconSource.fetchAppleLyricsOutcome(
    application: Application,
    baseSong: LocalSong,
    configuredSources: List<Source>,
    preferredSourceOverride: Source?,
    strictSource: Boolean,
): OnlineLyricTargeter.FetchOutcome {
    val shouldTryLunaBeat = preferredSourceOverride == Source.LB ||
        (preferredSourceOverride == null && isLunaBeatWordLyricsEnabled())
    var lunaBeatStatus: AppleMissingLyricsSourceStatus? = null
    if (shouldTryLunaBeat) {
        val lookup = LunaBeatTtmlRepository.findWordTimedByAppleMusicId(
            context = application,
            appleMusicId = baseSong.id.orEmpty(),
        )
        lunaBeatStatus = lookup.toSourceStatus()
        lookup.match?.let { match ->
            return OnlineLyricTargeter.FetchOutcome(
                lines = match.parsed.lrcLines,
                wordLines = match.parsed.wordLines,
                sourceStatuses = listOf(lunaBeatStatus),
                selectedSource = Source.LB,
                rawAppleTtml = match.rawTtml,
                sourceLyricId = match.hubId,
                sourceLyricSha256 = match.sha256,
            )
        }
        if (strictSource || preferredSourceOverride == Source.LB && !isFillMissingLyricsEnabled()) {
            return OnlineLyricTargeter.FetchOutcome(
                sourceStatuses = listOfNotNull(lunaBeatStatus),
            )
        }
    }

    val onlinePreferredSource = preferredSourceOverride?.takeUnless { it == Source.LB }
    val onlineOutcome = OnlineLyricTargeter.fetchBestLyricWithNearMiss(
        context = application,
        pkgName = LyriconSource.APPLE_MUSIC_PACKAGE,
        title = baseSong.name.orEmpty(),
        artist = baseSong.artist.orEmpty(),
        durationMs = baseSong.duration,
        originalTitle = baseSong.metadata
            ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_TITLE),
        originalArtist = baseSong.metadata
            ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_ARTIST),
        preferOriginalMetadata = shouldPreferAppleOriginalMetadata(),
        preferredSource = onlinePreferredSource,
        fallbackToOtherSources = !strictSource,
        sourceOrder = if (strictSource && onlinePreferredSource != null) {
            listOf(onlinePreferredSource)
        } else {
            configuredSources
        },
        statusSourceOrder = if (isFillMissingLyricsEnabled()) {
            // 严格来源切换也要补齐其他来源的状态，否则弹窗
            // 会出现「未检索/检索失败」的假失败。
            OnlineTranslationSourcePreferences.defaultOrder
        } else {
            null
        },
        album = MediaMetadataHelper
            .getMediaInfo(application, LyriconSource.APPLE_MUSIC_PACKAGE, HookLogger)
            .album,
        collectSourceStatuses = isFillMissingLyricsEnabled(),
    )
    return if (lunaBeatStatus == null) {
        onlineOutcome
    } else {
        onlineOutcome.copy(
            sourceStatuses = AppleMissingLyricsSourceMetadata.mergeStatuses(
                previous = listOf(lunaBeatStatus),
                incoming = onlineOutcome.sourceStatuses,
            )
        )
    }
}

internal fun LunaBeatLookupResult.toSourceStatus(): AppleMissingLyricsSourceStatus =
    AppleMissingLyricsSourceStatus(
        source = Source.LB.name,
        searched = searched,
        found = match != null,
        wordTimed = match != null,
        lineCount = match?.parsed?.wordLines?.size ?: 0,
    )

/** 全中文歌词不携带在线翻译：正文若全为中文，在线载荷的翻译列整体剔除。 */
internal fun stripFullyChineseTranslations(lines: List<LrcLine>): List<LrcLine> {
    if (!ChineseLyricsPolicy.isFullyChineseLrc(lines)) return lines
    return lines.map { it.copy(translation = null) }
}

internal fun OnlineLyricTargeter.FetchOutcome.stripFullyChineseTranslations():
    OnlineLyricTargeter.FetchOutcome {
    val lines = lines ?: return this
    val stripped = stripFullyChineseTranslations(lines)
    return if (stripped === lines) this else copy(lines = stripped)
}

internal fun LyriconSource.applyFallbackResult(
    generation: Int,
    baseSong: LocalSong,
    outcome: OnlineLyricTargeter.FetchOutcome,
    application: Application,
    fallbackEnabled: Boolean,
    requestedSource: Source? = null,
) {
    // 全中文正文不携带在线翻译：无歌词兜底/来源切换载荷的翻译列（常见为伴唱标注）
    // 一并剔除，后续逐字与行级映射都以此为源。
    val outcome = outcome.stripFullyChineseTranslations()
    val sourceSwitchRequest = activeSourceSwitchTraceRequest(baseSong.id)
        ?.takeIf { request ->
            request.contentType == "lyrics" &&
                (requestedSource == null || requestedSource == request.requestedSource)
        }
    sourceSwitchCoreStage(
        request = sourceSwitchRequest,
        stage = "fallback_result_applying",
        details = "generation=$generation,currentGeneration=$fallbackGeneration," +
            "selected=${outcome.selectedSource ?: "none"}," +
            "lines=${outcome.lines?.size ?: 0},wordLines=${outcome.wordLines?.size ?: 0}",
    )
    val nativeSong = currentAppleSong
    val sameTrack = nativeSong != null && isSameTrack(nativeSong, baseSong)
    val nativeSupplement = isMissingLyricsSupplement(nativeSong)
    val nativeSongHasNativeLyrics = hasAppleNativeLyrics(nativeSong)
    val pendingSourceRequest = pendingLyricsSourceRequest
    val manualLyricsSourceSwitch = requestedSource != null &&
        pendingSourceRequest?.songId == baseSong.id &&
        pendingSourceRequest?.requestedSource == requestedSource
    val automaticLunaBeatOverride = requestedSource == Source.LB &&
        outcome.selectedSource == Source.LB &&
        isLunaBeatWordLyricsEnabled()
    val requestStillCurrent = nativeSong != null && acceptsAppleOnlineLyricResult(
        generation = generation,
        currentGeneration = fallbackGeneration,
        sameTrack = sameTrack,
        currentNativeLyrics = currentAppleHasNativeLyrics,
        currentSongHasNativeLyrics = nativeSongHasNativeLyrics,
        manualSourceSwitch = manualLyricsSourceSwitch,
        automaticLunaBeatOverride = automaticLunaBeatOverride,
    )
    if (!requestStillCurrent) {
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "fallback_result_rejected",
            details = "generation=$generation,currentGeneration=$fallbackGeneration," +
                "sameTrack=$sameTrack,currentNative=$currentAppleHasNativeLyrics," +
                "currentSongHasNative=$nativeSongHasNativeLyrics," +
                "manual=$manualLyricsSourceSwitch",
        )
        diagnostic(
            "Apple Music 在线兜底结果已过期: title=${baseSong.name}, " +
                "generation=$generation, currentGeneration=$fallbackGeneration, " +
                "sameTrack=$sameTrack, currentNative=$currentAppleHasNativeLyrics, " +
                "currentSongHasNative=$nativeSongHasNativeLyrics, " +
                "currentSupplement=$nativeSupplement, currentId=${nativeSong?.id}, " +
                "manualLyricsSourceSwitch=$manualLyricsSourceSwitch, " +
                "resultLines=${outcome.lines?.size ?: 0}, " +
                "resultWordLines=${outcome.wordLines?.size ?: 0}, " +
                "selected=${outcome.selectedSource?.name}, " +
                "statuses=${outcome.sourceStatuses.joinToString { it.source + ":" + it.found }}"
        )
        return
    }

    fallbackJob = null
    var supplementSong: LocalSong? = null
    var enrichedLrcLines: List<LrcLine>? = null
    if (isFillMissingLyricsEnabled() || outcome.selectedSource == Source.LB) {
        val previousSourceInfo = AppleMissingLyricsSourceMetadata.decode(
            selectedSource = nativeSong.metadata
                ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE),
            encodedStatuses = nativeSong.metadata
                ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE_STATUSES),
        )
        val mergedStatuses = AppleMissingLyricsSourceMetadata.mergeStatuses(
            previous = previousSourceInfo?.statuses.orEmpty(),
            incoming = outcome.sourceStatuses,
        )
        val sourceInfo = AppleMissingLyricsSourceInfo(
            selectedSource = outcome.selectedSource?.name,
            statuses = mergedStatuses,
        )
        enrichedLrcLines = outcome.lines?.let { lines ->
            preservePreviousTranslations(nativeSong, lines)
        }
        val supplementLrcLines = enrichedLrcLines ?: outcome.lines
        val supplementBuildMode = when {
            !outcome.wordLines.isNullOrEmpty() -> "word"
            !supplementLrcLines.isNullOrEmpty() -> "line"
            else -> "none"
        }
        supplementSong = AppleMissingLyricsSongMapper.map(
            baseSong = baseSong,
            wordLines = outcome.wordLines,
            lrcLines = supplementLrcLines,
            sourceInfo = sourceInfo,
            rawAppleTtml = outcome.rawAppleTtml,
            sourceLyricId = outcome.sourceLyricId,
            sourceLyricSha256 = outcome.sourceLyricSha256,
        )
        diagnostic(
            "Apple Music 无歌词补充构建: id=${baseSong.id}, " +
                "mode=$supplementBuildMode, " +
                "wordLines=${outcome.wordLines?.size ?: 0}, " +
                "lineLines=${supplementLrcLines?.size ?: 0}, " +
                "builtLines=${supplementSong?.lyrics.orEmpty().size}"
        )
        if (supplementSong != null) {
            if (outcome.selectedSource == Source.LB) {
                confirmedLyricsSourceSelection = ConfirmedLyricsSourceSelection(
                    songId = baseSong.id.orEmpty(),
                    source = Source.LB.name,
                )
            }
            val publishStartedAtNanos = SystemClock.elapsedRealtimeNanos()
            val published = directBridge?.publishMissingLyricsSupplement(supplementSong) == true
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "supplement_binder_published",
                details = "generation=$generation,elapsedMs=" +
                    ((SystemClock.elapsedRealtimeNanos() - publishStartedAtNanos) /
                        1_000_000.0) +
                    ",published=$published,lines=${supplementSong.lyrics.orEmpty().size}," +
                    "mode=$supplementBuildMode",
            )
            diagnostic(
                "Apple Music 无歌词补充回传: id=${supplementSong.id}, " +
                    "lines=${supplementSong.lyrics.orEmpty().size}, published=$published"
            )
            // 后续在线翻译请求必须把这份补充歌词当作匹配基准；空歌词的 Apple
            // 占位不能再作为 currentAppleSong，否则翻译结果会在 apply guard 被丢弃。
            currentAppleSong = supplementSong
            currentAppleHasNativeLyrics = false
        } else if (requestedSource == null) {
            directBridge?.clearMissingLyricsSupplement(baseSong.id)
            diagnostic("Apple Music 无歌词补充未命中: title=${baseSong.name}")
        } else {
            diagnostic(
                "Apple Music 歌词来源未命中，保留当前补充歌词: " +
                    "id=${baseSong.id}, requested=$requestedSource"
            )
        }
        completePendingLyricsSourceRequest(
            song = supplementSong,
            requestedSource = requestedSource,
        )
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "fallback_result_applied",
            details = "generation=$generation,supplement=${supplementSong != null}," +
                "source=${outcome.selectedSource ?: "none"}",
        )
    }

    if (!fallbackEnabled && supplementSong == null) {
        if (outcome.lines == null) {
            diagnostic("Apple Music 在线兜底未命中: title=${baseSong.name}")
            requestOriginalMetadata(baseSong, "lyrics_fallback_miss")
        }
        return
    }

    val fallbackSong = (enrichedLrcLines ?: outcome.lines)?.let {
        OnlineFallbackSongMapper.map(baseSong, it)
    }
    if (fallbackSong == null && supplementSong == null) {
        diagnostic("Apple Music 在线兜底未命中: title=${baseSong.name}")
        requestOriginalMetadata(baseSong, "lyrics_fallback_miss")
        return
    }
    fallbackSongActive = true
    MediaMetadataHelper.getPlaybackProgress(application, LyriconSource.APPLE_MUSIC_PACKAGE)
        .position
        .takeIf { it >= 0L }
        ?.let { lastAdjustedPosition = it }
    val displayFallbackSong = supplementSong ?: requireNotNull(fallbackSong)
    HookLogger.i(
        LyriconSource.TAG,
        "Apple Music 在线兜底命中: title=${baseSong.name}, " +
            "lines=${displayFallbackSong.lyrics.orEmpty().size}, " +
            "translations=${displayFallbackSong.lyrics.orEmpty().count {
                OnlineTranslationContentPolicy.isMeaningful(it.translation)
            }}"
    )
    val fallbackHasTranslation = hasTranslation(displayFallbackSong)
    publishAppleSong(
        displayFallbackSong,
        restorePosition = true,
        onlineTranslationMatched = fallbackHasTranslation
    )
    if (!fallbackHasTranslation) {
        val onlineTranslationRunning =
            onlineTranslationJob?.isActive == true || onlineMatchedTranslationActive
        val onlineTranslationScheduled = supplementSong != null &&
            isAppleTranslationEnrichmentEnabled() &&
            scheduleOnlineTranslation(supplementSong)
        if (!onlineTranslationRunning && !onlineTranslationScheduled) {
            sink?.onOnlineTranslationUnavailable(displayFallbackSong)
        }
    } else if (supplementSong != null && isAppleTranslationEnrichmentEnabled()) {
        // 来源切换后的新正文可能复用了旧翻译，但行结构仍需要重新匹配；只要
        // 还有缺口，scheduleOnlineTranslation 会基于新载荷补齐，不会因为已有
        // 一部分翻译就跳过本次独立翻译请求。
        scheduleOnlineTranslation(supplementSong)
    }
    startMediaPositionPolling()
}

/**
 * 歌词来源切换的新载荷通常不带翻译。先按行时间轴把旧歌词里的翻译带过去，
 * 避免 Apple Music、超级岛和 AOD 在独立翻译链路完成前出现翻译空白。
 */
internal fun LyriconSource.preservePreviousTranslations(
    previousSong: LocalSong?,
    lines: List<LrcLine>,
): List<LrcLine> {
    val previousLines = previousSong?.lyrics.orEmpty()
    return lines.map { line ->
        if (OnlineTranslationContentPolicy.isMeaningful(line.translation)) return@map line
        val normalizedText = normalizeLyricText(line.content)
        val previous = previousLines.firstOrNull { previousLine ->
            previousLine.begin == line.startTimeMs &&
                normalizeLyricText(previousLine.text) == normalizedText
        } ?: previousLines.firstOrNull { it.begin == line.startTimeMs }
        val previousTranslation = previous?.translation
            ?.takeIf(OnlineTranslationContentPolicy::isMeaningful)
            ?: return@map line
        line.copy(translation = previousTranslation)
    }
}

internal fun LyriconSource.normalizeLyricText(text: String?): String =
    text.orEmpty().replace(Regex("\\s+"), " ").trim()

internal fun LyriconSource.cancelFallback(clearAppleSong: Boolean, reason: String) {
    if (fallbackDelayRunnable != null || fallbackJob?.isActive == true || fallbackSongActive) {
        diagnostic(
            "Apple Music 在线兜底取消: reason=$reason, " +
                "clearAppleSong=$clearAppleSong, title=${currentAppleSong?.name}"
        )
    }
    fallbackGeneration += 1
    fallbackDelayRunnable?.let(mainHandler::removeCallbacks)
    fallbackDelayRunnable = null
    fallbackJob?.cancel()
    fallbackJob = null
    fallbackSongActive = false
    stopMediaPositionPolling()
    if (clearAppleSong) {
        currentAppleSong = null
        currentAppleHasNativeLyrics = false
    }
}

/** 偏好变化后重新决定第三方歌曲的在线策略（椒盐支持在线歌词兜底与优先在线源）。 */
internal fun LyriconSource.reevaluateThirdPartyOnlineMatching(song: LocalSong) {
    val playerPackage = activeCentralPlayerPackageName
    val matchingEnabled = isOnlineTranslationEnabledFor(playerPackage)
    val hasLyrics = !song.lyrics.isNullOrEmpty()
    val preferOnline = isSaltPreferOnlineEnabled()
    when {
        playerPackage == OnlineTranslationSourcePreferences.SALT_PACKAGE &&
            matchingEnabled && (preferOnline || !hasLyrics) ->
            scheduleThirdPartyFallback(song, 0L)
        matchingEnabled && hasLyrics && needsOnlineEnrichment(song) ->
            scheduleOnlineTranslation(song)
        else -> sink?.onOnlineTranslationUnavailable(song)
    }
}

/**
 * 椒盐音乐在线歌词兜底：优先使用在线源时立即取词，否则等待椒盐 Pack 的
 * 本地歌词结果，超时后在线兜底。
 */
internal fun LyriconSource.scheduleThirdPartyFallback(baseSong: LocalSong, delayMs: Long) {
    val playerPackage = activeCentralPlayerPackageName ?: return
    if (playerPackage != OnlineTranslationSourcePreferences.SALT_PACKAGE) return
    if (baseSong.name.isNullOrBlank()) return
    if (!isOnlineTranslationEnabledFor(playerPackage)) return
    if (OnlineTranslationSourcePreferences.orderedSources(prefs).isEmpty()) return
    thirdPartyFallbackSongActive = false
    val generation = thirdPartyFallbackRequest.schedule(
        delayMs = delayMs,
        query = {
            val application = app
            if (application == null) {
                diagnostic(
                    "椒盐音乐在线兜底无法启动: reason=application_unavailable, " +
                        "title=${baseSong.name}"
                )
                null
            } else {
                fallbackRequestMutex.withLock {
                    diagnostic(
                        "椒盐音乐在线兜底开始: title=${baseSong.name}, " +
                            "artist=${baseSong.artist}, " +
                            "preferOnline=${isSaltPreferOnlineEnabled()}"
                    )
                    val lines = OnlineLyricTargeter.fetchBestLyric(
                        context = application,
                        pkgName = playerPackage,
                        title = baseSong.name.orEmpty(),
                        artist = baseSong.artist.orEmpty(),
                        durationMs = baseSong.duration,
                        sourceOrder = OnlineTranslationSourcePreferences.orderedSources(prefs),
                        album = MediaMetadataHelper
                            .getMediaInfo(application, playerPackage, HookLogger)
                            .album,
                    )
                    lines
                        ?.let(::stripFullyChineseTranslations)
                        ?.let { OnlineFallbackSongMapper.map(baseSong, it) }
                }
            }
        },
        apply = { requestGeneration, fallbackSong ->
            applyThirdPartyFallbackResult(requestGeneration, baseSong, fallbackSong)
        },
        failed = { error ->
            debugError("椒盐音乐在线兜底失败: title=${baseSong.name}", error)
        },
    )
    diagnostic(
        "椒盐音乐在线兜底已调度: title=${baseSong.name}, delayMs=$delayMs, " +
            "generation=$generation"
    )
}

private fun LyriconSource.applyThirdPartyFallbackResult(
    generation: Int,
    baseSong: LocalSong,
    fallbackSong: LocalSong?,
) {
    val playerPackage = activeCentralPlayerPackageName
    val song = currentThirdPartySong
    val requestStillCurrent = generation == thirdPartyFallbackRequest.snapshot().generation &&
        playerPackage == OnlineTranslationSourcePreferences.SALT_PACKAGE &&
        isOnlineTranslationEnabledFor(playerPackage) &&
        song != null && isSameTrack(song, baseSong) &&
        (isSaltPreferOnlineEnabled() || song.lyrics.isNullOrEmpty())
    if (!requestStillCurrent) {
        diagnostic(
            "椒盐音乐在线兜底结果已过期: title=${baseSong.name}, " +
                "generation=$generation, currentGeneration=${thirdPartyFallbackRequest.snapshot().generation}"
        )
        return
    }
    if (fallbackSong == null) {
        thirdPartyFallbackSongActive = false
        diagnostic("椒盐音乐在线兜底未命中: title=${baseSong.name}")
        return
    }
    thirdPartyFallbackSongActive = true
    currentPublishedThirdPartySong = fallbackSong
    HookLogger.i(
        LyriconSource.TAG,
        "椒盐音乐在线兜底命中: title=${baseSong.name}, " +
            "lines=${fallbackSong.lyrics.orEmpty().size}, " +
            "translations=${fallbackSong.lyrics.orEmpty().count {
                OnlineTranslationContentPolicy.isMeaningful(it.translation)
            }}"
    )
    publishSong(fallbackSong, restorePosition = true)
}

internal fun LyriconSource.cancelThirdPartyFallback(reason: String) {
    if (thirdPartyFallbackRequest.snapshot().pending ||
        thirdPartyFallbackSongActive
    ) {
        diagnostic(
            "椒盐音乐在线兜底取消: reason=$reason, " +
                "title=${currentThirdPartySong?.name}"
        )
    }
    thirdPartyFallbackRequest.cancel()
    thirdPartyFallbackSongActive = false
}


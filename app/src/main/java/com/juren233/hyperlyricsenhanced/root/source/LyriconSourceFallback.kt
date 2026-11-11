/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.root.LyricsAvailability
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
import com.juren233.hyperlyricsenhanced.online.source.ttml.CatalogPlatform
import com.juren233.hyperlyricsenhanced.online.source.ttml.TtmlCatalogSources
import com.juren233.hyperlyricsenhanced.online.source.ttml.TtmlLyricMatch
import com.juren233.hyperlyricsenhanced.online.source.ttml.toSourceStatus
import com.juren233.hyperlyricsenhanced.online.utils.ChineseUtils
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.MediaCardDiagnosticLogger
import com.juren233.hyperlyricsenhanced.contract.AppleDirectBridgeContract
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** 该次检索是否由来源弹窗里的手动歌词源选择发起（请求已在入口处按开关校验过）。 */
internal fun LyriconSource.isManualLyricsSourceSwitch(
    baseSong: LocalSong,
    requestedSource: Source?,
    target: OnlineTranslationTarget = activeOnlineTranslationTarget,
): Boolean = requestedSource != null &&
    isAlwaysShowSourceSwitchEnabled() &&
    target.manual.lyricsRequestToComplete(requestedSource)?.songId == baseSong.id

internal fun LyriconSource.scheduleFallback(
    baseSong: LocalSong,
    delayMs: Long,
    preferredSourceOverride: Source? = null,
    strictSource: Boolean = false,
    target: OnlineTranslationTarget = activeOnlineTranslationTarget,
) {
    val sourceSwitchRequest = activeSourceSwitchTraceRequest(baseSong.id)
        ?.takeIf { request ->
            request.contentType == "lyrics" &&
                preferredSourceOverride == request.requestedSource
        }
    val fallbackEnabled = isOnlineTranslationEnabledFor(LyriconSource.APPLE_MUSIC_PACKAGE)
    val supplementEnabled = isFillMissingLyricsEnabled() || isLineToWordLyricsEnabled()
    val untimedNativeFallback = allowsUntimedAppleOnlineFallback(
        appOnlineEnabled = fallbackEnabled,
        untimedNative = hasUntimedAppleNativeLyrics(baseSong),
    )
    // 目录类 TTML 源按 Apple Music ID 直取，不依赖歌名，故歌名为空也允许发起。
    val ttmlRequested = preferredSourceOverride?.isTtmlCatalog == true ||
        (preferredSourceOverride == null && isLineToWordLyricsEnabled())
    // 来源弹窗里手动选的歌词源（始终显示切换源按钮）不受各自动开关限制。
    val manualLyricsSwitch = isManualLyricsSourceSwitch(baseSong, preferredSourceOverride, target)
    if (
        (baseSong.name.isNullOrBlank() && !ttmlRequested) ||
        (!fallbackEnabled && !supplementEnabled && !untimedNativeFallback && !manualLyricsSwitch &&
            // 应用内检索只响应来源弹窗的明确请求，不看各自动开关。
            !target.inAppOnly)
    ) {
        debug(
            "补充检索未发起: id=${baseSong.id}, 歌名为空=${baseSong.name.isNullOrBlank()}, " +
                "目录请求=$ttmlRequested, 应用在线=$fallbackEnabled, 补充开关=$supplementEnabled, " +
                "无时间轴回退=$untimedNativeFallback"
        )
        return
    }
    val configuredSources = OnlineTranslationSourcePreferences.orderedSources(prefs)
    if (configuredSources.isEmpty() && !ttmlRequested) {
        debug("补充检索未发起: id=${baseSong.id}, 没有已启用来源")
        return
    }
    debug(
        "补充检索已排程: id=${baseSong.id}, 指定源=${preferredSourceOverride ?: "无"}, " +
            "严格=$strictSource, 来源=${configuredSources.joinToString("+")}"
    )
    val previousSnapshot = target.fallback.snapshot()
    val generation = target.fallback.invalidate(mainHandler::removeCallbacks)
    sourceSwitchCoreStage(
        request = sourceSwitchRequest,
        stage = "fallback_scheduled",
        details = "generation=${previousSnapshot.generation}->$generation,delayMs=$delayMs," +
            "strict=$strictSource,preferred=${preferredSourceOverride ?: "none"}," +
            "order=${configuredSources.joinToString("+")}," +
            "cancelledJobActive=${previousSnapshot.jobActive}," +
            "cancelledDelayPending=${previousSnapshot.delayPending}",
    )

    lateinit var delayedSearch: Runnable
    delayedSearch = Runnable {
        if (!target.fallback.isCurrent(generation)) {
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "fallback_delay_abandoned",
                details = "generation=$generation,currentGeneration=" +
                    "${target.fallback.generation()}",
            )
            return@Runnable
        }
        target.fallback.clearDelayIfCurrent(delayedSearch)
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
        val worker = fallbackScope.launch {
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
                    if (!target.fallback.isCurrent(generation)) {
                        sourceSwitchCoreStage(
                            request = sourceSwitchRequest,
                            stage = "fallback_search_abandoned",
                            details = "generation=$generation,currentGeneration=" +
                                "${target.fallback.generation()}",
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
                        fallbackEnabled = fallbackEnabled,
                        // 先到结果上岛预览只属于正式路径；应用内检索不上岛。
                        onEarly = if (target.inAppOnly) {
                            null
                        } else {
                            val islandPreview: suspend (OnlineLyricTargeter.FetchOutcome) -> Unit = { early ->
                                mainHandler.post {
                                    applyFallbackIslandPreview(generation, baseSong, early)
                                }
                            }
                            islandPreview
                        },
                        target = target,
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
                            if (target.inAppOnly) {
                                applyInAppFallbackResult(
                                    generation = generation,
                                    baseSong = baseSong,
                                    rawOutcome = outcome,
                                    requestedSource = preferredSourceOverride,
                                )
                            } else {
                                applyFallbackResult(
                                    generation = generation,
                                    baseSong = baseSong,
                                    outcome = outcome,
                                    application = application,
                                    fallbackEnabled = fallbackEnabled,
                                    requestedSource = preferredSourceOverride,
                                )
                            }
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
                    details = "generation=$generation,currentGeneration=" +
                        "${target.fallback.generation()}",
                )
                throw e
            } catch (e: Exception) {
                sourceSwitchCoreStage(
                    request = sourceSwitchRequest,
                    stage = "fallback_worker_failed",
                    details = "generation=$generation,error=${e.javaClass.simpleName}",
                )
                debugError("Apple Music 在线兜底失败: title=${baseSong.name}", e)
                // 歌词可用性汇总属于活动播放者的时间轴；应用内检索失败不得计入。
                if (!target.inAppOnly) LyricsAvailability.onThirdPartySettled()
            }
        }
        target.fallback.attachJob(worker)
    }
    target.fallback.registerDelay(delayedSearch)
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

/**
 * @param onEarly 「自动」来源下 ttml 与搜索源同时开始时，先到的结果通过它交给超级岛先显示；
 *   最终结果仍作为返回值，按 ttml 优先（AMLL 高于 LB）、其次搜索源裁决，Apple Music 应用内只用最终结果。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun LyriconSource.fetchAppleLyricsOutcome(
    application: Application,
    baseSong: LocalSong,
    configuredSources: List<Source>,
    preferredSourceOverride: Source?,
    strictSource: Boolean,
    fallbackEnabled: Boolean,
    onEarly: (suspend (OnlineLyricTargeter.FetchOutcome) -> Unit)? = null,
    target: OnlineTranslationTarget = activeOnlineTranslationTarget,
): OnlineLyricTargeter.FetchOutcome {
    val ttmlOnlyRequest = preferredSourceOverride?.isTtmlCatalog == true
    val manualTtmlSwitch = ttmlOnlyRequest &&
        activeSourceSwitchTraceRequest(baseSong.id)?.let { request ->
            request.contentType == "lyrics" && request.requestedSource == preferredSourceOverride
        } == true
    val searchSources = configuredSources.filterNot(Source::isTtmlCatalog)
    val leadingTtmlSources = configuredSources.takeWhile(Source::isTtmlCatalog)
    val trailingTtmlSources = configuredSources.drop(leadingTtmlSources.size)
        .filter(Source::isTtmlCatalog)
    // 原生已有歌词时，自动检索只负责翻译等补充，不应让 TTML 源抢占正文；
    // 逐行转逐字（TTML 源请求）与手动来源切换不受此限。
    val automaticTtmlAllowed = preferredSourceOverride == null &&
        (!hasAppleNativeLyrics(baseSong) || hasUntimedAppleNativeLyrics(baseSong))
    val ttmlStatuses = mutableListOf<AppleMissingLyricsSourceStatus>()
    suspend fun lookupTtml(sources: List<Source>): OnlineLyricTargeter.FetchOutcome? {
        for (source in sources) {
            val catalog = TtmlCatalogSources.of(source) ?: continue
            val lookup = catalog.findWordTimed(
                context = application,
                platform = CatalogPlatform.APPLE_MUSIC,
                songId = baseSong.id.orEmpty(),
            )
            ttmlStatuses += lookup.toSourceStatus(source)
            debug(
                "目录源查询: id=${baseSong.id}, source=$source, 已查=${lookup.searched}, " +
                    "命中=${lookup.match != null}, 行数=${lookup.match?.parsed?.wordLines?.size ?: 0}, " +
                    "带翻译行=${lookup.match?.parsed?.lrcLines?.count { !it.translation.isNullOrBlank() } ?: 0}, " +
                    "带音译行=${lookup.match?.parsed?.lrcLines?.count { !it.romanization.isNullOrBlank() } ?: 0}"
            )
            lookup.match?.let { return it.toFetchOutcome(ttmlStatuses.toList()) }
        }
        return null
    }
    val onlinePreferredSource = preferredSourceOverride?.takeUnless(Source::isTtmlCatalog)
    // 来源状态原本只在「为无歌词的歌曲补充歌词」开启时收集；手动歌词源切换同样需要。
    val fillMissingLyricsEnabled = isFillMissingLyricsEnabled() ||
        isManualLyricsSourceSwitch(baseSong, preferredSourceOverride, target) ||
        // 应用内检索（含只为弹窗收集各来源状态的检索）总是需要来源状态。
        target.inAppOnly
    val album = MediaMetadataHelper
        .getMediaInfo(application, LyriconSource.APPLE_MUSIC_PACKAGE, HookLogger)
        .album
    suspend fun fetchOnline(
        sources: List<Source>,
        includeOtherStatuses: Boolean,
        allowArtistIdentityNearMiss: Boolean = false,
    ): OnlineLyricTargeter.FetchOutcome = OnlineLyricTargeter.fetchBestLyricWithNearMiss(
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
        sourceOrder = sources,
        statusSourceOrder = if (includeOtherStatuses && fillMissingLyricsEnabled) {
            // 严格来源切换也要补齐其他来源的状态，否则弹窗
            // 会出现「未检索/检索失败」的假失败。
            OnlineTranslationSourcePreferences.defaultOrder
        } else {
            null
        },
        album = album,
        collectSourceStatuses = fillMissingLyricsEnabled,
        allowArtistIdentityNearMiss = allowArtistIdentityNearMiss,
    )
    val untimedNative = preferredSourceOverride == null && hasUntimedAppleNativeLyrics(baseSong)
    val raceEligible = onEarly != null && preferredSourceOverride == null && !strictSource &&
        !untimedNative && leadingTtmlSources.isNotEmpty() && searchSources.isNotEmpty() &&
        OnlineTranslationSourcePreferences.isAutoSelectBestSourceEnabled(prefs)
    var prefetchedOnline: OnlineLyricTargeter.FetchOutcome? = null
    suspend fun computeOnlineOutcome(): OnlineLyricTargeter.FetchOutcome = if (untimedNative && !fallbackEnabled) {
        // 「启用App」关闭时不做纯文本在线补充；也不得落到无歌词补充通道，
        // 否则 untimed 歌曲仍会经 supplement 发布改投在线词。
        diagnostic(
            "Apple Music 纯文本原生歌词兜底被启用App开关关闭: title=${baseSong.name}"
        )
        OnlineLyricTargeter.FetchOutcome()
    } else if (untimedNative) {
        var statuses = emptyList<AppleMissingLyricsSourceStatus>()
        var usableOutcome: OnlineLyricTargeter.FetchOutcome? = null
        val candidates = coroutineScope {
            searchSources.map { source ->
                async {
                    // 本地时长输入可能失真（如目录 178s 被报成 165s），纯文本替换
                    // 允许「标题+歌手双匹配但时长不符」的近失候选进入，最终由下方
                    // 正文重叠门禁把关身份。
                    // 单源异常不得连坐取消其余源，失败按空结果参与后续门禁排序。
                    val outcome = try {
                        fetchOnline(
                            sources = listOf(source),
                            includeOtherStatuses = false,
                            allowArtistIdentityNearMiss = true,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        diagnostic(
                            "Apple Music 纯文本原生歌词兜底单源失败: " +
                                "source=$source, error=${e.javaClass.simpleName}: ${e.message}"
                        )
                        OnlineLyricTargeter.FetchOutcome()
                    }
                    source to outcome
                }
            }.awaitAll()
        }
        for ((source, candidate) in candidates) {
            statuses = AppleMissingLyricsSourceMetadata.mergeStatuses(
                previous = statuses,
                incoming = candidate.sourceStatuses,
            )
            val candidateLines = candidate.lines
            if (!hasUsableTimedOnlineLyricsForUntimedApple(
                    nativeLineCount = baseSong.lyrics.orEmpty().size,
                    durationMs = baseSong.duration,
                    lines = candidateLines,
                )
            ) {
                if (!candidateLines.isNullOrEmpty()) {
                    diagnostic(
                        "Apple Music 纯文本原生歌词兜底跳过无效时间轴: " +
                            "source=$source, lines=${candidateLines.size}, " +
                            "timestamps=${candidateLines.map(LrcLine::startTimeMs).distinct().size}"
                    )
                }
                continue
            }
            val overlap = OnlineTranslationMatcher.matchUntimed(
                baseSong,
                candidateLines.orEmpty(),
            )
            if (acceptsUntimedAppleOnlineLyrics(
                    meaningfulNativeLineCount = baseSong.lyrics.orEmpty()
                        .count { !it.text.isNullOrBlank() },
                    match = overlap,
                )
            ) {
                usableOutcome = candidate
                break
            }
            diagnostic(
                "Apple Music 纯文本原生歌词兜底跳过重叠不达标候选: " +
                    "source=$source, lines=${candidateLines?.size ?: 0}, " +
                    "matched=${overlap.matchedCount}, " +
                    "confidence=${overlap.averageMatchScore}"
            )
        }
        (usableOutcome ?: OnlineLyricTargeter.FetchOutcome()).copy(sourceStatuses = statuses)
    } else {
        fetchOnline(
            sources = if (strictSource && onlinePreferredSource != null) {
                listOf(onlinePreferredSource)
            } else {
                searchSources
            },
            includeOtherStatuses = true,
        )
    }
    data class RaceResult(
        val ttml: OnlineLyricTargeter.FetchOutcome?,
        val search: OnlineLyricTargeter.FetchOutcome?,
    )

    // 「自动」来源：ttml 与搜索源同时开始，先到的先交给岛显示（onEarly），
    // 最终按 ttml 优先裁决，无论 ttml 多晚到；搜索源本身的内部执行方式不变。
    suspend fun raceTtmlAgainstSearch(): RaceResult = coroutineScope {
        val ttmlJobs = leadingTtmlSources.mapNotNull { source ->
            val catalog = TtmlCatalogSources.of(source) ?: return@mapNotNull null
            source to async {
                runCatching {
                    catalog.findWordTimed(
                        context = application,
                        platform = CatalogPlatform.APPLE_MUSIC,
                        songId = baseSong.id.orEmpty(),
                    )
                }
            }
        }
        val searchJob = async { runCatching { computeOnlineOutcome() } }

        fun recordTtmlLookups(upTo: Int) {
            for (index in 0..upTo) {
                val (source, job) = ttmlJobs[index]
                val lookup = job.getCompleted().getOrThrow()
                ttmlStatuses += lookup.toSourceStatus(source)
                debug(
                    "目录源查询: id=${baseSong.id}, source=$source, 已查=${lookup.searched}, " +
                        "命中=${lookup.match != null}, 行数=${lookup.match?.parsed?.wordLines?.size ?: 0}"
                )
            }
        }

        when (val final = LyricsRace.run(
            ttml = ttmlJobs.map { it.second },
            search = searchJob,
            ttmlHit = { it.match != null },
            searchHit = { !it.lines.isNullOrEmpty() },
            onEarlyTtml = { index, lookup ->
                val source = ttmlJobs[index].first
                onEarly?.invoke(lookup.match!!.toFetchOutcome(listOf(lookup.toSourceStatus(source))))
            },
            onEarlySearch = { outcome -> onEarly?.invoke(outcome) },
        )) {
            is LyricsRace.Final.Ttml -> {
                recordTtmlLookups(final.index)
                RaceResult(final.value.match!!.toFetchOutcome(ttmlStatuses.toList()), null)
            }
            is LyricsRace.Final.Search -> {
                recordTtmlLookups(ttmlJobs.lastIndex)
                RaceResult(null, final.value)
            }
        }
    }

    when {
        ttmlOnlyRequest -> {
            // 手动切换只查被点选的源；自动逐行转逐字依次尝试已启用的 TTML 源。
            val siblings = if (manualTtmlSwitch) emptyList() else configuredSources.filter(Source::isTtmlCatalog)
            val requested = listOfNotNull(preferredSourceOverride) +
                siblings.filterNot { it == preferredSourceOverride }
            lookupTtml(requested)?.let { return it }
            if (strictSource || !isFillMissingLyricsEnabled()) {
                return OnlineLyricTargeter.FetchOutcome(sourceStatuses = ttmlStatuses.toList())
            }
        }
        automaticTtmlAllowed -> if (raceEligible) {
            val raced = raceTtmlAgainstSearch()
            raced.ttml?.let { return it }
            prefetchedOnline = raced.search
        } else {
            lookupTtml(leadingTtmlSources)?.let { return it }
        }
    }

    val onlineOutcome = prefetchedOnline ?: computeOnlineOutcome()
    if (automaticTtmlAllowed && onlineOutcome.lines.isNullOrEmpty() && trailingTtmlSources.isNotEmpty()) {
        lookupTtml(trailingTtmlSources)?.let { return it }
    }
    return if (ttmlStatuses.isEmpty()) {
        onlineOutcome
    } else {
        onlineOutcome.copy(
            sourceStatuses = AppleMissingLyricsSourceMetadata.mergeStatuses(
                previous = ttmlStatuses.toList(),
                incoming = onlineOutcome.sourceStatuses,
            )
        )
    }
}

private fun TtmlLyricMatch.toFetchOutcome(
    statuses: List<AppleMissingLyricsSourceStatus>,
): OnlineLyricTargeter.FetchOutcome = OnlineLyricTargeter.FetchOutcome(
    lines = parsed.lrcLines,
    wordLines = parsed.wordLines,
    sourceStatuses = statuses,
    selectedSource = source,
    rawAppleTtml = rawTtml,
    sourceLyricId = sourceLyricId,
    sourceLyricSha256 = sha256,
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
        details = "generation=$generation,currentGeneration=" +
            "${appleFallbackRequest.generation()}," +
            "selected=${outcome.selectedSource ?: "none"}," +
            "lines=${outcome.lines?.size ?: 0},wordLines=${outcome.wordLines?.size ?: 0}",
    )
    val nativeSong = currentAppleSong
    val sameTrack = nativeSong != null && isSameTrack(nativeSong, baseSong)
    val nativeSupplement = isMissingLyricsSupplement(nativeSong)
    val nativeSongHasNativeLyrics = hasAppleNativeLyrics(nativeSong)
    val untimedNativeFallback = requestedSource == null && fallbackEnabled &&
        hasUntimedAppleNativeLyrics(baseSong) && hasUntimedAppleNativeLyrics(nativeSong)
    val pendingSourceRequest = manualSourceRequests.lyricsRequestToComplete(requestedSource)
    val manualLyricsSourceSwitch = requestedSource != null &&
        pendingSourceRequest?.songId == baseSong.id
    val automaticTtmlOverride = requestedSource?.isTtmlCatalog == true &&
        outcome.selectedSource?.isTtmlCatalog == true &&
        isLineToWordLyricsEnabled()
    val requestStillCurrent = nativeSong != null && acceptsAppleOnlineLyricResult(
        generation = generation,
        currentGeneration = appleFallbackRequest.generation(),
        sameTrack = sameTrack,
        currentNativeLyrics = currentAppleHasNativeLyrics,
        currentSongHasNativeLyrics = nativeSongHasNativeLyrics,
        manualSourceSwitch = manualLyricsSourceSwitch,
        automaticTtmlOverride = automaticTtmlOverride,
        untimedNativeFallback = untimedNativeFallback,
    )
    if (!requestStillCurrent) {
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "fallback_result_rejected",
            details = "generation=$generation,currentGeneration=" +
                "${appleFallbackRequest.generation()}," +
                "sameTrack=$sameTrack,currentNative=$currentAppleHasNativeLyrics," +
                "currentSongHasNative=$nativeSongHasNativeLyrics," +
                "manual=$manualLyricsSourceSwitch",
        )
        diagnostic(
            "Apple Music 在线兜底结果已过期: title=${baseSong.name}, " +
                "generation=$generation, currentGeneration=${appleFallbackRequest.generation()}, " +
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

    appleFallbackRequest.clearJob()
    var supplementSong: LocalSong? = null
    var enrichedLrcLines: List<LrcLine>? = null
    if (
        isFillMissingLyricsEnabled() || outcome.selectedSource?.isTtmlCatalog == true ||
        untimedNativeFallback || (manualLyricsSourceSwitch && isAlwaysShowSourceSwitchEnabled())
    ) {
        val built = buildFallbackSupplement(nativeSong, baseSong, outcome)
        enrichedLrcLines = built.enrichedLrcLines
        val supplementLrcLines = built.supplementLrcLines
        val supplementBuildMode = built.buildMode
        supplementSong = built.song
        diagnostic(
            "Apple Music 无歌词补充构建: id=${baseSong.id}, " +
                "mode=$supplementBuildMode, " +
                "wordLines=${outcome.wordLines?.size ?: 0}, " +
                "lineLines=${supplementLrcLines?.size ?: 0}, " +
                "builtLines=${supplementSong?.lyrics.orEmpty().size}"
        )
        if (supplementSong != null) {
            val ttmlSelected = outcome.selectedSource?.takeIf(Source::isTtmlCatalog)
            if (!untimedNativeFallback && ttmlSelected != null) {
                publication.confirmLyricsSource(ConfirmedLyricsSourceSelection(
                    songId = baseSong.id.orEmpty(),
                    source = ttmlSelected.name,
                ))
            }
            val publishStartedAtNanos = SystemClock.elapsedRealtimeNanos()
            val published = !untimedNativeFallback &&
                directBridge?.publishMissingLyricsSupplement(supplementSong) == true
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
                "Apple Music 在线歌词结果: id=${supplementSong.id}, " +
                    "lines=${supplementSong.lyrics.orEmpty().size}, " +
                    "islandOnly=$untimedNativeFallback, published=$published"
            )
            // 后续在线翻译请求必须把这份补充歌词当作匹配基准；空歌词的 Apple
            // 占位不能再作为 currentAppleSong，否则翻译结果会在 apply guard 被丢弃。
            if (!untimedNativeFallback) {
                publication.acceptAppleInput(supplementSong, false)
            }
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

    if (!fallbackEnabled && !untimedNativeFallback && supplementSong == null) {
        if (outcome.lines == null) {
            diagnostic("Apple Music 在线兜底未命中: title=${baseSong.name}")
            requestOriginalMetadata(baseSong, "lyrics_fallback_miss")
            LyricsAvailability.onThirdPartySettled()
        }
        return
    }

    val fallbackSong = (enrichedLrcLines ?: outcome.lines)?.let {
        OnlineFallbackSongMapper.map(baseSong, it)
    }
    if (fallbackSong == null && supplementSong == null) {
        diagnostic("Apple Music 在线兜底未命中: title=${baseSong.name}")
        requestOriginalMetadata(baseSong, "lyrics_fallback_miss")
        LyricsAvailability.onThirdPartySettled()
        return
    }
    selectAppleFallbackAtMediaPosition(application)
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
    val commitIsland = {
        publishAppleSong(
            displayFallbackSong,
            restorePosition = true,
            onlineTranslationMatched = fallbackHasTranslation
        )
        if (!fallbackHasTranslation) {
            val onlineTranslationRunning =
                onlineTranslationRunning || onlineTranslationResultReady || onlineMatchedTranslationActive
            val onlineTranslationScheduled = supplementSong != null &&
                !untimedNativeFallback &&
                (isAppleTranslationEnrichmentEnabled() || wantsSourceControlSearch(supplementSong)) &&
                scheduleOnlineTranslation(supplementSong)
            if (!onlineTranslationRunning && !onlineTranslationScheduled) {
                sink?.onOnlineTranslationUnavailable(displayFallbackSong)
            }
        } else if (supplementSong != null && !untimedNativeFallback &&
            (isAppleTranslationEnrichmentEnabled() || wantsSourceControlSearch(supplementSong))
        ) {
            // 来源切换后的新正文可能复用了旧翻译，但行结构仍需要重新匹配；只要
            // 还有缺口，scheduleOnlineTranslation 会基于新载荷补齐，不会因为已有
            // 一部分翻译就跳过本次独立翻译请求。
            scheduleOnlineTranslation(supplementSong)
        }
        fallbackIslandSwitch.clear()
    }
    // 岛上已经先显示了别的来源时，更高优先级的结果等到下一句歌词的起点再切过去，避免当前句跳变。
    val islandPreview = fallbackIslandSwitch.previewFor(generation)
    val switchTarget = islandPreview
        ?.takeIf { it.source != outcome.selectedSource }
        ?.let {
            OnlineTranslationBoundaryPolicy.nextCommitPosition(
                lines = it.song.lyrics.orEmpty(),
                currentPosition = LyriconDataBridge.currentPosition,
            )
        }
        ?.takeIf { it > LyriconDataBridge.currentPosition }
    if (switchTarget != null) {
        diagnostic(
            "Apple Music 岛端歌词来源下一句切换: id=${baseSong.id}, " +
                "from=${islandPreview?.source}, to=${outcome.selectedSource}, " +
                "target=$switchTarget, position=${LyriconDataBridge.currentPosition}"
        )
        fallbackIslandSwitch.defer(AppleFallbackIslandSwitch.Pending(generation, switchTarget) {
            mainHandler.post {
                if (appleFallbackRequest.isCurrent(generation)) commitIsland()
            }
        })
    } else {
        commitIsland()
    }
    startMediaPositionPolling()
}

/**
 * 把 Apple 侧切到「在线歌词」显示状态，并以系统 MediaSession 的当前进度作为起点。
 * 此状态下直连位置回调不再采用，调用方发布歌曲后须调用 [startMediaPositionPolling]。
 * 检索结果落地与应用内歌词来源转正共用。
 */
internal fun LyriconSource.selectAppleFallbackAtMediaPosition(application: Application) {
    publication.selectAppleFallback()
    MediaMetadataHelper.getPlaybackProgress(application, LyriconSource.APPLE_MUSIC_PACKAGE)
        .position
        .takeIf { it >= 0L }
        ?.let { applePositionState.lastAdjustedPosition = it }
}

/** 一次歌词检索结果映射成「无歌词补充」载荷的中间产物。 */
internal data class FallbackSupplement(
    val song: LocalSong?,
    val enrichedLrcLines: List<LrcLine>?,
    val supplementLrcLines: List<LrcLine>?,
    val buildMode: String,
)

/**
 * 把检索结果 [outcome] 映射成补充载荷：来源信息与已有状态合并，行翻译沿用 [nativeSong] 里按时间轴对得上的旧翻译。
 * 正式路径与应用内路径共用。
 */
internal fun LyriconSource.buildFallbackSupplement(
    nativeSong: LocalSong,
    baseSong: LocalSong,
    outcome: OnlineLyricTargeter.FetchOutcome,
): FallbackSupplement {
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
    val enrichedLrcLines = outcome.lines?.let { lines ->
        preservePreviousTranslations(nativeSong, lines)
    }
    val supplementLrcLines = enrichedLrcLines ?: outcome.lines
    val buildMode = when {
        !outcome.wordLines.isNullOrEmpty() -> "word"
        !supplementLrcLines.isNullOrEmpty() -> "line"
        else -> "none"
    }
    val song = AppleMissingLyricsSongMapper.map(
        baseSong = baseSong,
        wordLines = outcome.wordLines,
        lrcLines = supplementLrcLines,
        sourceInfo = sourceInfo,
        rawAppleTtml = outcome.rawAppleTtml,
        sourceLyricId = outcome.sourceLyricId,
        sourceLyricSha256 = outcome.sourceLyricSha256,
    )
    return FallbackSupplement(song, enrichedLrcLines, supplementLrcLines, buildMode)
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

/**
 * 自动来源竞速中先到的结果：只给超级岛先显示，不写入 Apple Music 应用内的歌词页，
 * 也不启动翻译衔接；应用内与翻译仍只在最终结果裁决后处理。
 */
internal fun LyriconSource.applyFallbackIslandPreview(
    generation: Int,
    baseSong: LocalSong,
    rawOutcome: OnlineLyricTargeter.FetchOutcome,
) {
    if (!appleFallbackRequest.isCurrent(generation)) return
    val nativeSong = currentAppleSong ?: return
    val accepted = acceptsAppleOnlineLyricResult(
        generation = generation,
        currentGeneration = appleFallbackRequest.generation(),
        sameTrack = isSameTrack(nativeSong, baseSong),
        currentNativeLyrics = currentAppleHasNativeLyrics,
        currentSongHasNativeLyrics = hasAppleNativeLyrics(nativeSong),
        manualSourceSwitch = false,
        automaticTtmlOverride = false,
        untimedNativeFallback = false,
    )
    if (!accepted) return
    val outcome = rawOutcome.stripFullyChineseTranslations()
    val previewSong = if (isFillMissingLyricsEnabled() || outcome.selectedSource?.isTtmlCatalog == true) {
        AppleMissingLyricsSongMapper.map(
            baseSong = baseSong,
            wordLines = outcome.wordLines,
            lrcLines = outcome.lines?.let { preservePreviousTranslations(nativeSong, it) } ?: outcome.lines,
            sourceInfo = AppleMissingLyricsSourceInfo(
                selectedSource = outcome.selectedSource?.name,
                statuses = outcome.sourceStatuses,
            ),
            rawAppleTtml = outcome.rawAppleTtml,
            sourceLyricId = outcome.sourceLyricId,
            sourceLyricSha256 = outcome.sourceLyricSha256,
        )
    } else {
        outcome.lines?.let { OnlineFallbackSongMapper.map(baseSong, preservePreviousTranslations(nativeSong, it)) }
    } ?: return
    if (previewSong.lyrics.isNullOrEmpty()) return
    diagnostic(
        "Apple Music 岛端先显示先到的歌词: id=${baseSong.id}, source=${outcome.selectedSource}, " +
            "lines=${previewSong.lyrics.orEmpty().size}"
    )
    publication.selectAppleFallback()
    fallbackIslandSwitch.showPreview(
        AppleFallbackIslandSwitch.Preview(generation, previewSong, outcome.selectedSource),
    )
    publishAppleSong(
        previewSong,
        restorePosition = true,
        onlineTranslationMatched = hasTranslation(previewSong),
    )
    startMediaPositionPolling()
}

internal fun LyriconSource.cancelFallback(
    clearAppleSong: Boolean,
    reason: String,
    target: OnlineTranslationTarget = activeOnlineTranslationTarget,
) {
    if (target.inAppOnly) {
        // 应用内检索没有岛端的兜底歌词、位置轮询可停；只作废它自己的检索任务。
        target.fallback.invalidate(mainHandler::removeCallbacks)
        return
    }
    fallbackIslandSwitch.clear()
    if (appleFallbackRequest.snapshot().pending || fallbackSongActive) {
        diagnostic(
            "Apple Music 在线兜底取消: reason=$reason, " +
                "clearAppleSong=$clearAppleSong, title=${currentAppleSong?.name}"
        )
    }
    appleFallbackRequest.invalidate(mainHandler::removeCallbacks)
    publication.cancelAppleFallback(clearSong = false)
    stopMediaPositionPolling()
    if (clearAppleSong) {
        publication.acceptAppleInput(null, false)
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
    publication.cancelThirdPartyFallback()
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
            LyricsAvailability.onThirdPartySettled()
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
        publication.cancelThirdPartyFallback()
        diagnostic("椒盐音乐在线兜底未命中: title=${baseSong.name}")
        // 本次在线兜底已结束且无结果：让「所有来源都没有歌词」的汇总重新判定。
        LyricsAvailability.onThirdPartySettled()
        return
    }
    publication.selectThirdPartyFallback()
    HookLogger.i(
        LyriconSource.TAG,
        "椒盐音乐在线兜底命中: title=${baseSong.name}, " +
            "lines=${fallbackSong.lyrics.orEmpty().size}, " +
            "translations=${fallbackSong.lyrics.orEmpty().count {
                OnlineTranslationContentPolicy.isMeaningful(it.translation)
            }}"
    )
    publishThirdPartySong(fallbackSong, restorePosition = true)
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
    publication.cancelThirdPartyFallback()
}

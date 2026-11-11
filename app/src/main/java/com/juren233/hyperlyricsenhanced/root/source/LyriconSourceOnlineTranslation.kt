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
import com.juren233.hyperlyricsenhanced.online.sanitizedForOnlineCandidate
import com.juren233.hyperlyricsenhanced.online.source.ttml.CatalogPlatform
import com.juren233.hyperlyricsenhanced.online.source.ttml.TtmlCatalogSources
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * [target] 决定检索作用于哪份状态：默认是活动播放者（Apple Music 或三方）的正式状态；
 * 应用内 Apple 歌曲用 [AppleInAppSong.target]，检索任务槽位、手动选择都独立，且结果不进岛端。
 */
internal fun LyriconSource.scheduleOnlineTranslation(
    baseSong: LocalSong,
    target: OnlineTranslationTarget = activeOnlineTranslationTarget,
    /** 刚改选了「Apple Music 原生」：不产生待完成请求，但同样是切换而不是首次检索。 */
    selectionChanged: Boolean = false,
): Boolean {
    val sourceSwitchRequest = activeSourceSwitchTraceRequest(baseSong.id)
    // 应用内歌曲一定属于 Apple Music，与当前活动播放者是谁无关。
    fun playerPackageNow(): String = if (target.inAppOnly) {
        LyriconSource.APPLE_MUSIC_PACKAGE
    } else {
        activeCentralPlayerPackageName ?: LyriconSource.APPLE_MUSIC_PACKAGE
    }
    val effectivePlayerPackage = playerPackageNow()
    val matchingEnabled = if (effectivePlayerPackage == LyriconSource.APPLE_MUSIC_PACKAGE) {
        isAppleTranslationEnrichmentEnabled()
    } else {
        isOnlineTranslationEnabledFor(effectivePlayerPackage)
    }
    val nativeLineCount = baseSong.lyrics.orEmpty().size
    val enrichmentNeeded = needsOnlineEnrichment(baseSong)
    val titlePresent = !baseSong.name.isNullOrBlank()
    // 来源弹窗（始终显示切换源按钮）需要的检索不看自动开关，也不看原生内容是否齐全。
    val sourceControlSearch = effectivePlayerPackage == LyriconSource.APPLE_MUSIC_PACKAGE &&
        wantsSourceControlSearch(baseSong, target)
    // 应用内检索只服务来源弹窗，不做自动补全（自动补全的结果要上岛，应用内模式不上岛）。
    val automaticFill = !target.inAppOnly && matchingEnabled && enrichmentNeeded
    sourceSwitchCoreStage(
        request = sourceSwitchRequest,
        stage = "translation_schedule_evaluated",
        details = "matchingEnabled=$matchingEnabled,nativeLines=$nativeLineCount," +
            "enrichmentNeeded=$enrichmentNeeded,titlePresent=$titlePresent," +
            "sourceControl=$sourceControlSearch,generation=${target.request.snapshot().generation}",
    )
    if (nativeLineCount == 0 || !titlePresent || (!automaticFill && !sourceControlSearch)) {
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "translation_schedule_skipped",
            details = "reason=precondition_failed",
        )
        pronunciationDiagnostic(
            "stage=request_schedule_skipped, reason=precondition_failed, " +
                "id=${baseSong.id}, prefsPresent=${prefs != null}, " +
                "matchingEnabled=$matchingEnabled, nativeLines=$nativeLineCount, " +
                "needsEnrichment=$enrichmentNeeded, titlePresent=$titlePresent, " +
                "sourceControl=$sourceControlSearch"
        )
        return false
    }
    fun sourceControl(): OnlineSourceControl? = if (sourceControlSearch) {
        OnlineSourceControl(
            automaticFill = automaticFill,
            translationNativeOnly = target.manual.isTranslationNativeOnly(),
            pronunciationNativeOnly = target.manual.isPronunciationNativeOnly(),
        )
    } else {
        null
    }
    val lyricsSourceStatuses = ConcurrentHashMap<Source, AppleMissingLyricsSourceStatus>()
    val attemptKey = translationIdentity(baseSong)
    // 切换来源的一轮不渐进发布，只在最后给弹窗一次完整结果（首次检索仍渐进，见 isSourceSwitchRound）。
    val sourceSwitchRound = target.isSourceSwitchRound(baseSong.id, selectionChanged)
    val requestSnapshot = target.request.snapshot()
    if (isAppleOnlineTranslationAttemptAlive(
            attemptMatches = requestSnapshot.attempt == attemptKey,
            requestRunning = requestSnapshot.running,
            resultReady = requestSnapshot.resultReady,
            matchedActive = !target.inAppOnly && onlineMatchedTranslationActive,
        )
    ) {
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "translation_schedule_skipped",
            details = "reason=attempt_already_recorded",
        )
        pronunciationDiagnostic(
            "stage=request_schedule_skipped, reason=attempt_already_recorded, " +
                "id=${baseSong.id}, attempt=$attemptKey"
        )
        return false
    }

    val previousJobActive = requestSnapshot.running
    val generation = target.request.begin(attemptKey, allowRestart = true) ?: return false
    pronunciationDiagnostic(
        "stage=request_scheduled, generation=$generation, id=${baseSong.id}, " +
            "attempt=$attemptKey, nativeLines=${baseSong.lyrics.orEmpty().size}"
    )
    sourceSwitchCoreStage(
        request = sourceSwitchRequest,
        stage = "translation_job_scheduled",
        details = "generation=$generation,cancelledJobActive=$previousJobActive",
    )

    fun postTranslationApply(
        selection: OnlineTranslationSelection?,
        publicationStage: LyriconSource.OnlineTranslationPublicationStage,
        reason: String,
    ) {
        val postedAtNanos = SystemClock.elapsedRealtimeNanos()
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "translation_apply_posted",
            details = "generation=$generation,publicationStage=$publicationStage," +
                "reason=$reason,resultPresent=${selection != null}",
        )
        target.request.markResultReady(generation)
        mainHandler.post {
            val applyStartedAtNanos = SystemClock.elapsedRealtimeNanos()
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "translation_prepare_enqueue_started",
                details = "generation=$generation,publicationStage=$publicationStage," +
                    "queueWaitMs=" +
                    ((applyStartedAtNanos - postedAtNanos) / 1_000_000.0),
            )
            try {
                applyOnlineTranslationResult(
                    generation = generation,
                    baseSong = baseSong,
                    selection = selection,
                    publicationStage = publicationStage,
                    target = target,
                )
            } finally {
                sourceSwitchCoreStage(
                    request = sourceSwitchRequest,
                    stage = "translation_prepare_enqueue_finished",
                    details = "generation=$generation,publicationStage=$publicationStage," +
                        "elapsedMs=" +
                        ((SystemClock.elapsedRealtimeNanos() - applyStartedAtNanos) /
                            1_000_000.0),
                )
            }
        }
    }

    target.request.launch(fallbackScope, generation) {
        val mutexWaitStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "translation_mutex_wait_started",
            details = "generation=$generation",
        )
        try {
            fallbackRequestMutex.withLock {
                sourceSwitchCoreStage(
                    request = sourceSwitchRequest,
                    stage = "translation_mutex_acquired",
                    details = "generation=$generation,waitMs=" +
                        ((SystemClock.elapsedRealtimeNanos() - mutexWaitStartedAtNanos) /
                            1_000_000.0),
                )
                if (generation != target.request.snapshot().generation) {
                    sourceSwitchCoreStage(
                        request = sourceSwitchRequest,
                        stage = "translation_search_abandoned",
                        details = "generation=$generation," +
                            "currentGeneration=${target.request.snapshot().generation}",
                    )
                    pronunciationDiagnostic(
                        "stage=request_abandoned, generation=$generation, id=${baseSong.id}, " +
                            "reason=generation_changed_before_start, " +
                            "currentGeneration=${target.request.snapshot().generation}"
                    )
                    return@withLock
                }
                val application = app ?: run {
                    pronunciationDiagnostic(
                        "stage=request_abandoned, generation=$generation, id=${baseSong.id}, " +
                            "reason=application_unavailable"
                    )
                    return@withLock
                }
                val configuredSources = configuredTranslationSources(playerPackageNow())
                if (configuredSources.isEmpty()) {
                    postTranslationApply(
                        selection = null,
                        publicationStage = LyriconSource.OnlineTranslationPublicationStage.SINGLE,
                        reason = "no_configured_sources",
                    )
                    return@withLock
                }
                val automaticSelection =
                    OnlineTranslationSourcePreferences.isAutoSelectBestSourceEnabled(prefs)
                val preferredSource = configuredSources.first()
                val alternativeSource = configuredSources.drop(1).firstOrNull()
                val requestedFirstSource = target.manual.requestedOnlineSource()
                val raceEnabled = automaticFill && automaticSelection &&
                    requestedFirstSource == null && !target.manual.hasTemporarySource
                val firstSource = requestedFirstSource
                    ?.takeIf(configuredSources::contains)
                    ?: preferredSource
                val remainingSources = listOf(firstSource) + configuredSources.filterNot {
                    it == firstSource
                }
                val playerPackage = playerPackageNow()
                val completeOnlinePronunciation = playerPackage == LyriconSource.APPLE_MUSIC_PACKAGE &&
                    ApplePronunciationVisibilityPolicy.allowsOnlineSupplementation(
                        song = baseSong,
                        hideMandarinPinyin = isHideMandarinPinyinEnabled(),
                    )
                val totalLineCount = baseSong.lyrics.orEmpty().sumOf { line ->
                    if (line.text.isNullOrBlank()) {
                        0
                    } else {
                        (if (!OnlineTranslationContentPolicy.isMeaningful(line.translation)) 1 else 0) +
                        (if (
                            completeOnlinePronunciation && line.roma.isNullOrBlank()
                        ) 1 else 0)
                    }
                }
                val mediaInfo = MediaMetadataHelper.getMediaInfo(application, playerPackage)
                val searchDuration = if (playerPackage == LyriconSource.APPLE_MUSIC_PACKAGE) {
                    AppleOnlineTranslationSearchDurationPolicy.resolve(
                        song = baseSong,
                        media = AppleOnlineTranslationSearchDurationPolicy.MediaSnapshot(
                            title = mediaInfo.title,
                            artist = mediaInfo.artist,
                            durationMs = mediaInfo.duration,
                        ),
                    )
                } else {
                    AppleOnlineTranslationSearchDurationPolicy.Resolution(
                        durationMs = baseSong.duration.takeIf { it > 0L }
                            ?: mediaInfo.duration,
                        mediaIdentityMatched = false,
                    )
                }
                diagnostic(
                    "在线歌词补全开始: player=$playerPackage, title=${baseSong.name}, " +
                        "artist=${baseSong.artist}, preferred=$preferredSource, " +
                        "first=$firstSource, requested=${requestedFirstSource ?: "none"}"
                )
                pronunciationDiagnostic(
                    "stage=search_duration_resolved, generation=$generation, id=${baseSong.id}, " +
                        "lyricDuration=${baseSong.duration}, mediaDuration=${mediaInfo.duration}, " +
                        "mediaTitle=${mediaInfo.title}, mediaArtist=${mediaInfo.artist}, " +
                        "identityMatched=${searchDuration.mediaIdentityMatched}, " +
                        "selectedDuration=${searchDuration.durationMs}"
                )
                pronunciationDiagnostic(
                    "stage=request_started, generation=$generation, id=${baseSong.id}, " +
                        "preferred=$preferredSource, order=${remainingSources.joinToString("+")}, " +
                        "pronunciationAllowed=$completeOnlinePronunciation, " +
                    "targetContent=$totalLineCount"
                )
                val searchStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                sourceSwitchCoreStage(
                    request = sourceSwitchRequest,
                    stage = "translation_search_started",
                    details = "generation=$generation,order=${remainingSources.joinToString("+")}," +
                        "race=$raceEnabled,targetContent=$totalLineCount",
                )
                val fetchedCandidates = linkedMapOf<Source, OnlineTranslationSelector.Candidate>()
                val answeredSources = linkedSetOf<Source>()
                val answeredCandidates = linkedMapOf<Source, OnlineTranslationSelector.Candidate>()
                if (remainingSources.size > 1) {
                    pronunciationDiagnostic(
                        "stage=race_started, generation=$generation, id=${baseSong.id}, " +
                            "sources=${remainingSources.joinToString("+")}"
                    )
                    fetchedCandidates += OnlineTranslationRace.run(
                        sources = remainingSources,
                        clockMs = SystemClock::elapsedRealtime,
                        fetch = { source ->
                            fetchOnlineTranslationCandidate(
                                application = application,
                                baseSong = baseSong,
                                source = source,
                                totalLineCount = totalLineCount,
                                generation = generation,
                                searchDurationMs = searchDuration.durationMs,
                                sourceControlSearch = sourceControlSearch,
                                onLyricsSourceStatus = { lyricsSourceStatuses[source] = it },
                                target = target,
                            )
                        },
                        onCompletion = { completion ->
                            val source = completion.source
                            val candidate = completion.value
                            pronunciationDiagnostic(
                                "stage=race_source_finished, generation=$generation, " +
                                    "id=${baseSong.id}, source=$source, elapsedMs=${completion.elapsedMs}, " +
                                    "found=${candidate != null}, error=${completion.error?.javaClass?.name ?: "none"}"
                            )
                            completion.error?.let { error ->
                                debugError(
                                    "在线翻译赛马来源失败: title=${baseSong.name}, source=$source",
                                    error,
                                )
                            }
                            answeredSources += source
                            candidate?.let { answeredCandidates[source] = it }
                            var publishedFirstNow = false
                            var publishedProgressNow = false
                            if (candidate != null) {
                                if (raceEnabled &&
                                    target.request.snapshot().firstPublished != generation &&
                                    candidate.matchedContentCount > 0
                                ) {
                                    val firstSelection = OnlineTranslationSelection(
                                        onlineLinesBySource = mapOf(source to candidate.onlineLines),
                                        requestedSources = listOf(source),
                                        defaultTranslationSource = source.takeIf {
                                            OnlineTranslationMatcher.contributesTranslation(
                                                baseSong,
                                                candidate.result,
                                            )
                                        },
                                        defaultPronunciationSource = source.takeIf {
                                            OnlineTranslationMatcher.contributesPronunciation(
                                                baseSong,
                                                candidate.result,
                                            )
                                        },
                                        sourceOrder = listOf(source),
                                        pronunciationRequested = completeOnlinePronunciation,
                                        control = sourceControl(),
                                        lyricsSourceStatuses = lyricsSourceStatuses.values.toList(),
                                    )
                                    target.request.markFirstPublished(generation)
                                    publishedFirstNow = true
                                    pronunciationDiagnostic(
                                        "stage=race_first_ready, generation=$generation, " +
                                            "id=${baseSong.id}, source=$source, " +
                                            "matchedContent=${candidate.matchedContentCount}"
                                    )
                                    postTranslationApply(
                                        selection = firstSelection,
                                        publicationStage =
                                            LyriconSource.OnlineTranslationPublicationStage.RACE_FIRST,
                                        reason = "race_first_ready",
                                    )
                                }
                            }
                            if (raceEnabled && answeredSources.size < remainingSources.size) {
                                // A slow source must not hold back what the others already found:
                                // publish the sources answered so far. Lyric content still moves
                                // only at a line boundary (see race_final_deferred), while the
                                // per-source menu state is published at once.
                                if (!publishedFirstNow &&
                                    target.request.snapshot().firstPublished == generation
                                ) {
                                    val partial = composeOnlineTranslationSelection(
                                        baseSong = baseSong,
                                        candidates = answeredCandidates,
                                        requestedSources = remainingSources.filter(answeredSources::contains),
                                        configuredSources = configuredSources,
                                        preferredSource = preferredSource,
                                        alternativeSource = alternativeSource,
                                        totalLineCount = totalLineCount,
                                        automaticSelection = automaticSelection,
                                        completeOnlinePronunciation = completeOnlinePronunciation,
                                        control = sourceControl(),
                                        lyricsSourceStatuses = lyricsSourceStatuses.values.toList(),
                                        target = target,
                                    )
                                    postTranslationApply(
                                        selection = partial.selection,
                                        publicationStage =
                                            LyriconSource.OnlineTranslationPublicationStage.RACE_FINAL,
                                        reason = "race_progress",
                                    )
                                    publishedProgressNow = true
                                }
                            }
                            if (sourceControlSearch && !sourceSwitchRound &&
                                answeredSources.size < remainingSources.size &&
                                !publishedFirstNow && !publishedProgressNow
                            ) {
                                // 来源弹窗不等最慢的来源：已答来源的菜单状态先发一版。赛马在本次
                                // 回调里已发布过的版本自带这些状态，不重复发；最后一个来源交给
                                // 检索结束后的完整合成。
                                val partial = composeOnlineTranslationSelection(
                                    baseSong = baseSong,
                                    candidates = answeredCandidates,
                                    requestedSources = remainingSources.filter(answeredSources::contains),
                                    configuredSources = configuredSources,
                                    preferredSource = preferredSource,
                                    alternativeSource = alternativeSource,
                                    totalLineCount = totalLineCount,
                                    automaticSelection = automaticSelection,
                                    completeOnlinePronunciation = completeOnlinePronunciation,
                                    control = sourceControl(),
                                    lyricsSourceStatuses = lyricsSourceStatuses.values.toList(),
                                    target = target,
                                )
                                postTranslationApply(
                                    selection = partial.selection,
                                    publicationStage = LyriconSource
                                        .OnlineTranslationPublicationStage.SOURCE_STATUS_PROGRESS,
                                    reason = "source_control_progress",
                                )
                            }
                        },
                    )
                } else {
                    remainingSources.forEachIndexed { index, source ->
                        val previous = fetchedCandidates.values.lastOrNull()
                        val explicitlyRequested =
                            target.manual.requestedTranslationSource() == source ||
                                target.manual.requestedPronunciationSource() == source
                        if (index > 0 && !automaticSelection && !explicitlyRequested &&
                            !sourceControlSearch &&
                            !OnlineTranslationSelector.shouldTryAlternative(previous, totalLineCount)
                        ) {
                            return@forEachIndexed
                        }
                        if (index > 0) {
                            diagnostic(
                                "在线翻译继续尝试后续来源: " +
                                    "title=${baseSong.name}, source=$source"
                            )
                        }
                        fetchOnlineTranslationCandidate(
                            application = application,
                            baseSong = baseSong,
                            source = source,
                            totalLineCount = totalLineCount,
                            generation = generation,
                            searchDurationMs = searchDuration.durationMs,
                            sourceControlSearch = sourceControlSearch,
                            onLyricsSourceStatus = { lyricsSourceStatuses[source] = it },
                            target = target,
                        )?.let { fetchedCandidates[source] = it }
                    }
                }
                val composed = composeOnlineTranslationSelection(
                    baseSong = baseSong,
                    candidates = fetchedCandidates,
                    requestedSources = remainingSources,
                    configuredSources = configuredSources,
                    preferredSource = preferredSource,
                    alternativeSource = alternativeSource,
                    totalLineCount = totalLineCount,
                    automaticSelection = automaticSelection,
                    completeOnlinePronunciation = completeOnlinePronunciation,
                    control = sourceControl(),
                    lyricsSourceStatuses = lyricsSourceStatuses.values.toList(),
                    target = target,
                )
                val candidates = fetchedCandidates
                val rankedCandidates = composed.rankedCandidates
                val selected = composed.selected
                val supplementalCandidates = rankedCandidates.drop(1)
                val selection = composed.selection
                val currentPublishedSong = (if (target.inAppOnly) {
                    inAppApple.publishedSong()
                } else if (playerPackage == LyriconSource.APPLE_MUSIC_PACKAGE) {
                    currentPublishedAppleSong
                } else {
                    currentPublishedThirdPartySong
                })
                    ?.takeIf { isSameTrack(it, baseSong) }
                    ?.let { publishedSong ->
                        ApplePronunciationVisibilityPolicy.filterSong(
                            song = publishedSong,
                            hideMandarinPinyin = isHideMandarinPinyinEnabled(),
                        )
                    }
                val mergedResult = selection.compose(baseSong, currentPublishedSong)
                val selectedTranslationSource = mergedResult
                    ?.song
                    ?.metadata
                    ?.getString(LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE)
                val selectedPronunciationSource = mergedResult
                    ?.song
                    ?.metadata
                    ?.getString(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE)
                diagnostic(
                    "在线翻译来源选择: player=$playerPackage, title=${baseSong.name}, " +
                    "preferred=$preferredSource, selected=${selected?.source}, " +
                    "translation=$selectedTranslationSource, " +
                    "pronunciation=$selectedPronunciationSource, " +
                    "compared=${rankedCandidates.size > 1}, automatic=$automaticSelection"
                )
                HookLogger.i(
                    LyriconSource.TAG,
                    "在线翻译来源选择: player=$playerPackage, title=${baseSong.name}, " +
                        "selected=${selected?.source}, " +
                        "translation=$selectedTranslationSource, " +
                        "pronunciation=$selectedPronunciationSource, " +
                        "compared=${rankedCandidates.size > 1}, automatic=$automaticSelection"
                )
                val selectedMatchedCount = selected?.result?.matchedCount ?: 0
                if (mergedResult != null && mergedResult.matchedCount > selectedMatchedCount) {
                    diagnostic(
                        "在线翻译已由后续来源补齐: player=$playerPackage, title=${baseSong.name}, " +
                            "source=${supplementalCandidates.joinToString("+") { it.source.name }}, " +
                            "matched=$selectedMatchedCount->${mergedResult.matchedCount}"
                    )
                }
                pronunciationDiagnostic(
                    "stage=selection_composed, generation=$generation, id=${baseSong.id}, " +
                        "candidateSources=${candidates.keys.joinToString("+")}, " +
                        "selected=${selected?.source}, translationSource=$selectedTranslationSource, " +
                        "pronunciationSource=$selectedPronunciationSource, " +
                        "resultPresent=${mergedResult != null}, " +
                        "resultRomanized=${mergedResult?.song?.lyrics.orEmpty().count { !it.roma.isNullOrBlank() }}"
                )
                if (raceEnabled) {
                    pronunciationDiagnostic(
                        "stage=race_finished, generation=$generation, id=${baseSong.id}, " +
                            "ranking=${rankedCandidates.joinToString(",") { candidate ->
                                "${candidate.source}:${formatMetric(OnlineTranslationSelector.quality(candidate, totalLineCount))}"
                            }}"
                    )
                }
                sourceSwitchCoreStage(
                    request = sourceSwitchRequest,
                    stage = "translation_search_finished",
                    details = "generation=$generation,elapsedMs=" +
                        ((SystemClock.elapsedRealtimeNanos() - searchStartedAtNanos) /
                            1_000_000.0) +
                        ",candidates=${candidates.keys.joinToString("+")}," +
                        "selected=${selected?.source ?: "none"}",
                )
                postTranslationApply(
                    selection = selection,
                    publicationStage = if (raceEnabled) {
                        LyriconSource.OnlineTranslationPublicationStage.RACE_FINAL
                    } else {
                        LyriconSource.OnlineTranslationPublicationStage.SINGLE
                    },
                    reason = "search_finished",
                )
            }
        } catch (e: CancellationException) {
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "translation_job_cancelled",
                details = "generation=$generation," +
                    "currentGeneration=${target.request.snapshot().generation}",
            )
            pronunciationDiagnostic(
                "stage=request_cancelled, generation=$generation, id=${baseSong.id}, " +
                    "currentGeneration=${target.request.snapshot().generation}"
            )
            throw e
        } catch (e: Exception) {
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "translation_job_failed",
                details = "generation=$generation,error=${e.javaClass.simpleName}",
            )
            pronunciationDiagnostic(
                "stage=request_failed, generation=$generation, id=${baseSong.id}, " +
                    "error=${e.javaClass.name}, message=${e.message}"
            )
            postTranslationApply(
                selection = null,
                publicationStage = LyriconSource.OnlineTranslationPublicationStage.SINGLE,
                reason = "search_failed",
            )
            debugError(
                "在线翻译匹配失败: player=$activeCentralPlayerPackageName, " +
                    "title=${baseSong.name}",
                e,
            )
        }
    }
    return true
}

/**
 * TTML 目录源（AMLL、LunaBeat）按 Apple Music ID 直取，不经歌名搜索。文件自带的逐行翻译与音译
 * 作为与其他来源同等的翻译/发音候选，由同一套匹配与选择逻辑处理。
 */
private suspend fun LyriconSource.fetchCatalogTranslationOutcome(
    application: Application,
    baseSong: LocalSong,
    source: Source,
    forceRefresh: Boolean,
): OnlineLyricTargeter.FetchOutcome {
    val catalog = TtmlCatalogSources.of(source) ?: return OnlineLyricTargeter.FetchOutcome()
    val lookup = catalog.findWordTimed(
        context = application,
        platform = CatalogPlatform.APPLE_MUSIC,
        songId = baseSong.id.orEmpty(),
        forceRefresh = forceRefresh,
    )
    val lines = lookup.match?.parsed?.lrcLines?.sanitizedForOnlineCandidate()
    debug(
        "目录源翻译候选: id=${baseSong.id}, source=$source, 已查=${lookup.searched}, " +
            "行数=${lines?.size ?: 0}, 带翻译行=${lines?.count { !it.translation.isNullOrBlank() } ?: 0}, " +
            "带音译行=${lines?.count { !it.romanization.isNullOrBlank() } ?: 0}"
    )
    return OnlineLyricTargeter.FetchOutcome(
        lines = lines?.takeIf { it.isNotEmpty() },
        wordLines = lookup.match?.parsed?.wordLines,
        selectedSource = source.takeIf { lines != null },
    )
}

internal suspend fun LyriconSource.fetchOnlineTranslationCandidate(
    application: Application,
    baseSong: LocalSong,
    source: Source,
    totalLineCount: Int,
    generation: Int,
    searchDurationMs: Long,
    sourceControlSearch: Boolean = false,
    onLyricsSourceStatus: ((AppleMissingLyricsSourceStatus) -> Unit)? = null,
    target: OnlineTranslationTarget = activeOnlineTranslationTarget,
): OnlineTranslationSelector.Candidate? {
    val sourceSwitchRequest = activeSourceSwitchTraceRequest(baseSong.id)
    val fetchStartedAtNanos = SystemClock.elapsedRealtimeNanos()
    sourceSwitchCoreStage(
        request = sourceSwitchRequest,
        stage = "translation_source_fetch_started",
        details = "generation=$generation,source=$source,durationMs=$searchDurationMs",
    )
    pronunciationDiagnostic(
        "stage=candidate_fetch_started, generation=$generation, id=${baseSong.id}, source=$source"
    )
    val mediaInfo = MediaMetadataHelper.getMediaInfo(application, LyriconSource.APPLE_MUSIC_PACKAGE, HookLogger)
    val originalAlbum = baseSong.metadata
        ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_ALBUM)
        ?.takeIf(String::isNotBlank)
    val albumForSearch = originalAlbum ?: mediaInfo.album
    val cacheSongId = baseSong.id?.takeIf(String::isNotBlank)
    // 只有点选这个来源的那一轮才强制重查未命中；之后重开弹窗等检索不再重复联网。
    val explicitlyRequested = target.manual.isRetryRequested(source)
    val queryKey = listOf(
        baseSong.name,
        baseSong.artist,
        baseSong.metadata?.getString(LyricMetadataKeys.APPLE_ORIGINAL_TITLE),
        baseSong.metadata?.getString(LyricMetadataKeys.APPLE_ORIGINAL_ARTIST),
        albumForSearch,
        originalAlbum,
        shouldPreferAppleOriginalMetadata().toString(),
        searchDurationMs.toString(),
    ).joinToString("\u0000") { it.orEmpty() }
    val cached = cacheSongId?.let { target.fetchCache.get(it, source, queryKey) }
    val fetchOutcome = when {
        cached is OnlineTranslationFetchCache.Entry.Hit -> cached.outcome
        cached is OnlineTranslationFetchCache.Entry.Miss && !explicitlyRequested ->
            OnlineLyricTargeter.FetchOutcome()
        else -> (
            if (source.isTtmlCatalog) {
                fetchCatalogTranslationOutcome(
                    application = application,
                    baseSong = baseSong,
                    source = source,
                    forceRefresh = cached is OnlineTranslationFetchCache.Entry.Miss && explicitlyRequested,
                )
            } else {
                OnlineLyricTargeter.fetchBestLyricWithNearMiss(
                    context = application,
                    pkgName = LyriconSource.APPLE_MUSIC_PACKAGE,
                    title = baseSong.name.orEmpty(),
                    artist = baseSong.artist.orEmpty(),
                    durationMs = searchDurationMs,
                    originalTitle = baseSong.metadata
                        ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_TITLE),
                    originalArtist = baseSong.metadata
                        ?.getString(LyricMetadataKeys.APPLE_ORIGINAL_ARTIST),
                    preferOriginalMetadata = shouldPreferAppleOriginalMetadata(),
                    preferredSource = source,
                    requireTranslation = false,
                    fallbackToOtherSources = false,
                    album = albumForSearch,
                    originalAlbum = originalAlbum,
                )
            }
            ).also { outcome ->
            if (cacheSongId != null) {
                target.fetchCache.put(
                    cacheSongId,
                    source,
                    queryKey,
                    if (outcome.lines != null || outcome.nearMiss?.lines != null) {
                        OnlineTranslationFetchCache.Entry.Hit(outcome)
                    } else {
                        OnlineTranslationFetchCache.Entry.Miss
                    },
                )
            }
        }
    }
    // 歌词来源弹窗用的是「手动切换歌词源」同一条取词结果：只有直接命中（非近失）才算可切换。
    onLyricsSourceStatus?.invoke(
        AppleMissingLyricsSourceStatus(
            source = source.name,
            searched = true,
            found = !fetchOutcome.lines.isNullOrEmpty(),
            wordTimed = !fetchOutcome.wordLines.isNullOrEmpty(),
            lineCount = fetchOutcome.wordLines?.size?.takeIf { it > 0 }
                ?: fetchOutcome.lines?.size
                ?: 0,
        )
    )
    val onlineLines = fetchOutcome.lines ?: fetchOutcome.nearMiss?.lines
    if (onlineLines == null) {
        sourceSwitchCoreStage(
            request = sourceSwitchRequest,
            stage = "translation_source_fetch_finished",
            details = "generation=$generation,source=$source,elapsedMs=" +
                ((SystemClock.elapsedRealtimeNanos() - fetchStartedAtNanos) /
                    1_000_000.0) +
                ",found=false",
        )
        pronunciationDiagnostic(
            "stage=candidate_fetch_finished, generation=$generation, id=${baseSong.id}, " +
                "source=$source, found=false"
        )
        diagnostic(
            "在线翻译候选未命中: player=$activeCentralPlayerPackageName, " +
                "title=${baseSong.name}, source=$source"
        )
        return null
    }
    val appleRequest = target.inAppOnly ||
        activeCentralPlayerPackageName == LyriconSource.APPLE_MUSIC_PACKAGE ||
        currentThirdPartySong == null
    val filteredOnlineLines = ApplePronunciationVisibilityPolicy.filterOnlineLines(
        song = baseSong,
        onlineLines = onlineLines,
        hideMandarinPinyin = appleRequest && isHideMandarinPinyinEnabled(),
    ).let { lines ->
        if (appleRequest) lines else lines.map { it.copy(romanization = null) }
    }
    val result = OnlineTranslationMatcher.apply(baseSong, filteredOnlineLines)
    val baseLines = baseSong.lyrics.orEmpty()
    val enrichedLines = result.song.lyrics.orEmpty()
    val matchedTranslationCount = baseLines.indices.count { index ->
        !OnlineTranslationContentPolicy.isMeaningful(baseLines[index].translation) &&
            OnlineTranslationContentPolicy.isMeaningful(
                enrichedLines.getOrNull(index)?.translation
            )
    }
    val matchedPronunciationCount = baseLines.indices.count { index ->
        baseLines[index].roma.isNullOrBlank() &&
            !enrichedLines.getOrNull(index)?.roma.isNullOrBlank()
    }
    if (fetchOutcome.nearMiss != null) {
        // 原生内容齐全的歌没有缺行可比对，近失候选的歌词重叠校验改用「原生内容留空」后的全量匹配。
        val verificationResult = if (sourceControlSearch) {
            OnlineTranslationMatcher.apply(
                OnlineSourceOverride.strip(baseSong, translation = true, pronunciation = true),
                filteredOnlineLines,
            )
        } else {
            null
        }
        val verification = if (verificationResult != null) {
            val sungLines = baseLines.count { !it.text.isNullOrBlank() }
            AppleOnlineTranslationNearMissPolicy.VerificationInputs(
                score = fetchOutcome.nearMiss.score,
                missingTranslationCount = sungLines,
                matchedTranslationCount =
                    OnlineSourceOverride.translationCoverage(verificationResult.song).matchedLines,
                missingPronunciationCount = sungLines,
                matchedPronunciationCount =
                    OnlineSourceOverride.pronunciationCoverage(verificationResult.song).matchedLines,
                averageMatchScore = verificationResult.averageMatchScore,
                durationVerified = fetchOutcome.nearMiss.durationVerified,
            )
        } else AppleOnlineTranslationNearMissPolicy.VerificationInputs(
            score = fetchOutcome.nearMiss.score,
            missingTranslationCount = baseLines.count {
                !OnlineTranslationContentPolicy.isMeaningful(it.translation)
            },
            matchedTranslationCount = matchedTranslationCount,
            missingPronunciationCount = baseLines.count { it.roma.isNullOrBlank() },
            matchedPronunciationCount = matchedPronunciationCount,
            averageMatchScore = result.averageMatchScore,
            durationVerified = fetchOutcome.nearMiss.durationVerified,
        )
        val coverage = AppleOnlineTranslationNearMissPolicy.contentCoverage(verification)
        if (!AppleOnlineTranslationNearMissPolicy.accepts(verification)) {
            sourceSwitchCoreStage(
                request = sourceSwitchRequest,
                stage = "translation_source_fetch_finished",
                details = "generation=$generation,source=$source,elapsedMs=" +
                    ((SystemClock.elapsedRealtimeNanos() - fetchStartedAtNanos) /
                        1_000_000.0) +
                    ",found=false,reason=near_miss_rejected," +
                    "matchedTranslation=$matchedTranslationCount," +
                    "matchedPronunciation=$matchedPronunciationCount",
            )
            pronunciationDiagnostic(
                "stage=near_miss_rejected, generation=$generation, id=${baseSong.id}, " +
                    "source=$source, score=${fetchOutcome.nearMiss.score}, " +
                    "coverage=${formatMetric(coverage)}, " +
                    "confidence=${formatMetric(result.averageMatchScore)}, " +
                    "matchedTranslation=$matchedTranslationCount, " +
                    "matchedPronunciation=$matchedPronunciationCount, " +
                    "durationVerified=${fetchOutcome.nearMiss.durationVerified}"
            )
            diagnostic(
                "在线翻译近失候选未通过歌词重叠校验: " +
                    "player=$activeCentralPlayerPackageName, " +
                    "title=${baseSong.name}, source=$source, " +
                    "score=${fetchOutcome.nearMiss.score}, coverage=${formatMetric(coverage)}"
            )
            return null
        }
        pronunciationDiagnostic(
            "stage=near_miss_accepted, generation=$generation, id=${baseSong.id}, " +
                "source=$source, score=${fetchOutcome.nearMiss.score}, " +
                "coverage=${formatMetric(coverage)}, " +
                "confidence=${formatMetric(result.averageMatchScore)}, " +
                "matchedTranslation=$matchedTranslationCount, " +
                "matchedPronunciation=$matchedPronunciationCount, " +
                "durationVerified=${fetchOutcome.nearMiss.durationVerified}"
        )
    }
    val candidate = OnlineTranslationSelector.Candidate(
        source = source,
        onlineLineCount = onlineLines.size,
        translatedLineCount = onlineLines.count {
            OnlineTranslationContentPolicy.isMeaningful(it.translation)
        },
        result = result,
        romanizedLineCount = filteredOnlineLines.count {
            !it.romanization.isNullOrBlank()
        },
        matchedContentCount = matchedTranslationCount + matchedPronunciationCount,
        onlineLines = filteredOnlineLines,
    )
    sourceSwitchCoreStage(
        request = sourceSwitchRequest,
        stage = "translation_source_fetch_finished",
        details = "generation=$generation,source=$source,elapsedMs=" +
            ((SystemClock.elapsedRealtimeNanos() - fetchStartedAtNanos) /
                1_000_000.0) +
            ",found=true,rawLines=${onlineLines.size}," +
            "matchedTranslation=$matchedTranslationCount," +
            "matchedPronunciation=$matchedPronunciationCount",
    )
    pronunciationDiagnostic(
        "stage=candidate_fetch_finished, generation=$generation, id=${baseSong.id}, " +
            "source=$source, found=true, rawLines=${onlineLines.size}, " +
            "rawRomanized=${onlineLines.count { !it.romanization.isNullOrBlank() }}, " +
            "filteredLines=${filteredOnlineLines.size}, " +
            "filteredRomanized=${candidate.romanizedLineCount}"
    )
    pronunciationDiagnostic(
        "stage=matcher_finished, generation=$generation, id=${baseSong.id}, source=$source, " +
            "matchedLines=${result.matchedCount}, matchedTranslation=$matchedTranslationCount, " +
            "matchedPronunciation=$matchedPronunciationCount, " +
            "resultRomanized=${enrichedLines.count { !it.roma.isNullOrBlank() }}"
    )
    diagnostic(
        "在线翻译候选: player=$activeCentralPlayerPackageName, " +
            "title=${baseSong.name}, source=$source, " +
            "lines=${candidate.onlineLineCount}, " +
            "translated=${candidate.translatedLineCount}, " +
            "romanized=${candidate.romanizedLineCount}, " +
            "matchedLines=${result.matchedCount}, " +
            "matchedContent=${candidate.matchedContentCount}/$totalLineCount, " +
            "coverage=${formatMetric(OnlineTranslationSelector.coverage(candidate, totalLineCount))}, " +
            "confidence=${formatMetric(result.averageMatchScore)}, " +
            "quality=${formatMetric(OnlineTranslationSelector.quality(candidate, totalLineCount))}"
    )
    return candidate
}

internal data class ComposedOnlineTranslation(
    val rankedCandidates: List<OnlineTranslationSelector.Candidate>,
    val selected: OnlineTranslationSelector.Candidate?,
    val selection: OnlineTranslationSelection,
)

/**
 * Ranks the candidates fetched so far and builds the selection from them. [requestedSources]
 * lists only the sources that have answered: a source still searching must stay unreported
 * so the menus show it as searching rather than as failed.
 */
internal fun LyriconSource.composeOnlineTranslationSelection(
    baseSong: LocalSong,
    candidates: Map<Source, OnlineTranslationSelector.Candidate>,
    requestedSources: List<Source>,
    configuredSources: List<Source>,
    preferredSource: Source,
    alternativeSource: Source?,
    totalLineCount: Int,
    automaticSelection: Boolean,
    completeOnlinePronunciation: Boolean,
    control: OnlineSourceControl? = null,
    lyricsSourceStatuses: List<AppleMissingLyricsSourceStatus> = emptyList(),
    target: OnlineTranslationTarget = activeOnlineTranslationTarget,
): ComposedOnlineTranslation {
    val rankedCandidates = if (automaticSelection) {
                    OnlineTranslationSelector.rank(
                        candidates = candidates.values,
                        totalLineCount = totalLineCount,
                        tieBreakOrder = OnlineTranslationSourcePreferences.defaultOrder,
                    )
                } else {
                    val preferredCandidate = candidates[preferredSource]
                    val alternativeCandidate = alternativeSource?.let(candidates::get)
                    val selectedCandidate = OnlineTranslationSelector.select(
                        preferred = preferredCandidate,
                        alternative = alternativeCandidate,
                        totalLineCount = totalLineCount,
                    )
                    buildList {
                        selectedCandidate?.let(::add)
                        listOfNotNull(preferredCandidate, alternativeCandidate)
                            .filterNot(::contains)
                            .forEach(::add)
                        candidates.values.filterNot(::contains).forEach(::add)
                    }
                }
                val selected = rankedCandidates.firstOrNull()
                val supplementalCandidates = rankedCandidates.drop(1)
                val defaultTranslationCandidate = rankedCandidates
                    .firstOrNull {
                        OnlineTranslationMatcher.contributesTranslation(baseSong, it.result)
                    }
                val defaultPronunciationCandidate = rankedCandidates
                    .firstOrNull {
                        OnlineTranslationMatcher.contributesPronunciation(baseSong, it.result)
                    }
                val selection = OnlineTranslationSelection(
                    onlineLinesBySource = candidates.mapValues { it.value.onlineLines },
                    requestedSources = requestedSources,
                    defaultTranslationSource = defaultTranslationCandidate?.source,
                    defaultPronunciationSource = defaultPronunciationCandidate?.source,
                    forcedTranslationSource = target.manual.requestedTranslationSource(),
                    forcedPronunciationSource = target.manual.requestedPronunciationSource(),
                    sourceOrder = if (automaticSelection) {
                        rankedCandidates.map { it.source }
                    } else {
                        configuredSources
                    },
                    pronunciationRequested = completeOnlinePronunciation,
                    control = control,
                    lyricsSourceStatuses = lyricsSourceStatuses,
                )
    return ComposedOnlineTranslation(rankedCandidates, selected, selection)
}
